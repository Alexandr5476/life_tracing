"""PR37 small Number fallback: real v10 SQL, exact reference and native VM work.

Run normally for the corrected dispatch, or --repository-ref 3cfc9e4 for fail-first.
Host SQLite evidence complements, never replaces, physical Room/API execution.
"""

import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from statistics_duration_probe import ROOT, query, reference
from statistics_number_probe import insert
from statistics_number_s3_probe import fixture
from statistics_review_probe import schema, plan, distribution
from statistics_shared_lineage_probe import measured, rank_query


def broad_value(index, field):
    """java.util.Random.nextLong, identical to the Room fixture (including signed low word)."""
    if index < 2:
        return [-(2**63), 2**63 - 1][index]
    seed = (37 + index * 1_000_003 + field) ^ 0x5DEECE66D

    def next32():
        nonlocal seed
        seed = (seed * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        value = seed >> 16
        return value if value < 2**31 else value - 2**32

    return (next32() << 32) + next32()


def small_fixture(db, kind, fields, mixed, rows):
    old, _ = fixture(db, kind, max(7_000, 7 * rows), fields)
    for field in range(fields):
        if mixed and field == 0:
            db.execute(f"DELETE FROM {kind}_execution_field_values WHERE snapshot_field_id=?",
                       (f"{kind}-field-{field}",))
        else:
            db.execute(f"INSERT OR REPLACE INTO {kind}_execution_field_values "
                       f"({kind}_execution_id,snapshot_field_id,number_scaled) "
                       f"SELECT id,?,1 FROM {kind}_executions WHERE statistics_series_id=?",
                       (f"{kind}-field-{field}", old))
    new = kind + "-new"
    insert(db, "statistics_series", dict(id=new, kind=kind.upper(), display_name="New", created_at_ms=0))
    snapshot = dict(db.execute(f"SELECT * FROM {kind}_snapshots LIMIT 1").fetchone())
    insert(db, kind + "_snapshots", dict(snapshot, id=new + "-snapshot", statistics_series_id=new))
    owner = "snapshot_id" if kind == "activity" else "sequence_snapshot_id"
    for row in db.execute(f"SELECT * FROM {kind}_snapshot_fields").fetchall():
        insert(db, kind + "_snapshot_fields", dict(row, id=new + "-" + row["id"], **{owner: new + "-snapshot"}))
    original = dict(db.execute(f"SELECT * FROM {kind}_executions LIMIT 1").fetchone())
    expected = {f"{kind}-source-{field}": [] for field in range(fields)}
    for index in range(rows):
        execution = f"{new}-{index}"
        insert(db, kind + "_executions", dict(original, id=execution, snapshot_id=new + "-snapshot",
               statistics_series_id=new))
        for field in range(fields):
            if field % 2 and index == rows - 1:
                continue
            value = broad_value(index, field)
            insert(db, kind + "_execution_field_values", {kind + "_execution_id": execution,
                   "snapshot_field_id": f"{new}-{kind}-field-{field}", "number_scaled": value})
            expected[f"{kind}-source-{field}"].append(value)
    db.execute(f"UPDATE {kind}_templates SET deleted_at_ms=2")
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    return new, expected


def grouped(db, kind, series, summaries, measure_work=True):
    searches = {r["source_field_id"]: [r["minimum_scaled"], r["maximum_scaled"]] for r in summaries}
    counts = {r["source_field_id"]: r["sample_count"] for r in summaries}
    selected = {}
    work = passes = 0
    while searches:
        passes += 1
        assert passes <= 65
        pivots = {field: (bounds[0] + bounds[1]) // 2 for field, bounds in searches.items()}
        sql = rank_query(kind, pivots)
        args = (series, None, None, None)
        details = plan(db, sql, args)
        paths = [line for line in details if "SEARCH " in line.upper()]
        assert len(paths) == 3 and "executions" in paths[0], details
        assert not any("CORRELATED" in line.upper() for line in details), details
        if measure_work:
            work += measured(db, sql, args)
        ranks = db.execute(sql, args).fetchall()
        assert len(ranks) == len(pivots)
        for row in ranks:
            field = row["source_field_id"]
            count, below = counts[field], row["at_or_below_count"]
            low, high = searches[field]
            assert row["sample_count"] == count
            if below == (count - 1) // 2 + 1:
                a = row["maximum_at_or_below"]
                b = row["minimum_above"] if count % 2 == 0 else a
            elif low == high:
                assert below > (count - 1) // 2
                a = b = low
            else:
                searches[field][0 if below <= (count - 1) // 2 else 1] = (
                    row["minimum_above"] if below <= (count - 1) // 2 else row["maximum_at_or_below"])
                continue
            selected[field] = dict(sample_count=count, selected_count=2 - count % 2,
                                   lower_median_scaled=a, upper_median_scaled=b)
            del searches[field]
    return selected, work, passes


def run_case(db, kind, series, expected, mixed, rows, corrected):
    bindings = dict(seriesId=series, startDate=None, endDate=None)
    summaries = db.execute(query(kind + "NumberSummaries"), bindings).fetchall()
    assert {r["source_field_id"] for r in summaries} == set(expected)
    candidates = db.execute(query(kind + "NumberExecutionCandidateCount"), bindings).fetchone()[0]
    budget = 2 * candidates
    remaining = []
    common_work = scalar_work = 0
    for row in sorted(summaries, key=lambda r: r["source_field_id"]):
        field = row["source_field_id"]
        params = dict(bindings, fieldId=field, expectedCount=row["sample_count"])
        # Shared probe budget, exactly as in groupedNumberDistributions.
        accepted = False
        if budget:
            cap = dict(fieldId=field, limit=budget + 1)
            sql = query(kind + "NumberFieldCandidateCount")
            common_work += measured(db, sql, cap)
            count = db.execute(sql, cap).fetchone()[0]
            accepted = count < cap["limit"]
            budget = budget - count if accepted else 0
            assert accepted == (mixed and field.endswith("-0"))
        sql = query(kind + ("DenseSeriesNumberSelection" if accepted else "SeriesNumberSelection"))
        details = plan(db, sql, params)
        paths = [line for line in details if "SEARCH " in line.upper()]
        assert ("fields" if accepted else "executions") in paths[0], details
        assert not any("CORRELATED" in line.upper() for line in details), details
        selection = db.execute(sql, params).fetchone()
        exact = reference(expected[field].copy())
        assert distribution([row], selection) == exact
        assert exact[0] == rows - int(field.endswith("-1"))  # Missing is not zero.
        assert abs(exact[1]) > 2**63 - 1
        # Individual canonical SQL parity, including its own exact bit summary.
        bits = db.execute(query(kind + "NumberBitCounts"), params).fetchall()
        individual = db.execute(query(kind + "NumberSelection"), params).fetchone()
        assert distribution(bits, individual) == exact
        if accepted:
            common_work += measured(db, sql, params)
        else:
            remaining.append(row)
            scalar_work += measured(db, sql, params)
        print(kind, "mixed" if mixed else len(expected), field, "scalar plan", details)
    selected, grouped_work, passes = grouped(db, kind, series, remaining)
    assert passes >= 8, "Broad fixture must exercise substantially more than three rank passes"
    for row in remaining:
        assert distribution([row], selected[row["source_field_id"]]) == reference(expected[row["source_field_id"]].copy())
    assert scalar_work < grouped_work, (scalar_work, grouped_work)
    # Trace the selected production dispatch; single populated Field already bypasses grouped selection.
    statements = []
    db.set_trace_callback(statements.append)
    if len(summaries) == 1 or corrected:
        for row in remaining:
            params = dict(bindings, fieldId=row["source_field_id"], expectedCount=row["sample_count"])
            db.execute(query(kind + "SeriesNumberSelection"), params).fetchone()
    else:
        grouped(db, kind, series, remaining, measure_work=False)
    db.set_trace_callback(None)
    rank_reads = sum(" AS at_or_below_count" in sql for sql in statements)
    median_reads = sum(" AS lower_median_scaled" in sql for sql in statements)
    print(kind, "fields", len(expected), "mixed", mixed, "rows", rows, "unresolved", len(remaining),
          "grouped passes", passes, "common VM", common_work, "scalar VM", scalar_work,
          "grouped VM", grouped_work, "executed rank reads", rank_reads, "scalar reads", median_reads)
    assert rank_reads == 0, "W1: small fallback unnecessarily repeats the complete grouped history traversal"
    assert median_reads == len(remaining) <= 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-ref")
    parser.add_argument("--rows", type=int, default=1_000)
    parser.add_argument("--kind", choices=("activity", "sequence"))
    parser.add_argument("--case", choices=("single", "two", "mixed"))
    args = parser.parse_args()
    assert args.rows >= 1_000
    path = "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt"
    source = (subprocess.check_output(["git", "show", args.repository_ref + ":" + path], cwd=ROOT).decode("utf-8")
              if args.repository_ref else (ROOT / path).read_text(encoding="utf-8"))
    # Deliberately narrow dispatch recognition; the Room test asserts executed production statements.
    corrected = "if (remaining.size <= 2)" in source
    print("Host SQLite", sqlite3.sqlite_version, "small fallback enabled", corrected)
    for case, fields, mixed in (("single", 1, False), ("two", 2, False), ("mixed", 2, True)):
        if args.case and case != args.case:
            continue
        for kind in ((args.kind,) if args.kind else ("activity", "sequence")):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "small.db"
                with closing(sqlite3.connect(path)) as db:
                    schema(db)
                    series, expected = small_fixture(db, kind, fields, mixed, args.rows)
                    db.commit()
                with closing(sqlite3.connect(path)) as db:
                    db.row_factory = sqlite3.Row
                    run_case(db, kind, series, expected, mixed, args.rows, corrected)
    print("PASS exact distributions, reopened v10, scalar/individual parity, bounded statements and VM work")


if __name__ == "__main__":
    main()
