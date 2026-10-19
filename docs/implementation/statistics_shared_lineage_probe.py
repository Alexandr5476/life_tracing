"""F1 shared-lineage work regression using production SQL and v10 indexes.

Host SQLite work evidence only; Room execution belongs to the five-API gate.
"""

import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import re

from statistics_duration_probe import ROOT

from statistics_duration_probe import query, reference
from statistics_number_probe import insert
from statistics_number_s3_probe import fixture
from statistics_review_probe import schema, plan, distribution


def measured(db, sql, bindings):
    steps = 0

    def progress():
        nonlocal steps
        steps += 1
        return 0

    db.set_progress_handler(progress, 1)
    try:
        # Consume the cursor, never a full sample list.
        for _ in db.execute(sql, bindings):
            pass
    finally:
        db.set_progress_handler(None, 0)
    return steps


def shared_fixture(db, kind, fields):
    old, _ = fixture(db, kind, 6000, fields)
    # Every old lineage exceeds even the old 2Q+1 budget at P=24.
    for field in range(fields):
        db.execute(f"INSERT OR REPLACE INTO {kind}_execution_field_values "
                   f"({kind}_execution_id,snapshot_field_id,number_scaled) "
                   f"SELECT id,?,1 FROM {kind}_executions WHERE statistics_series_id=?",
                   (f"{kind}-field-{field}", old))
    new = kind + "-new"
    insert(db, "statistics_series", dict(id=new, kind=kind.upper(), display_name="New", created_at_ms=0))
    snapshot = dict(db.execute(f"SELECT * FROM {kind}_snapshots LIMIT 1").fetchone())
    insert(db, kind + "_snapshots", dict(snapshot, id=new + "-snapshot", statistics_series_id=new))
    for row in db.execute(f"SELECT * FROM {kind}_snapshot_fields").fetchall():
        owner = "snapshot_id" if kind == "activity" else "sequence_snapshot_id"
        insert(db, kind + "_snapshot_fields", dict(row, id=new + "-" + row["id"], **{owner: new + "-snapshot"}))
    original = dict(db.execute(f"SELECT * FROM {kind}_executions LIMIT 1").fetchone())
    expected = {}
    for field in range(fields):
        expected[f"{kind}-source-{field}"] = []
    for index in range(100):
        execution = f"{new}-{index}"
        insert(db, kind + "_executions", dict(original, id=execution, snapshot_id=new + "-snapshot",
               statistics_series_id=new))
        for field in range(fields):
            if field % 2 and index == 99:
                continue
            value = ([-(2**63), 2**63 - 1, 0, 2**63 - 1][index % 4] if field % 2 == 0 else
                     [-(2**63), 2**63 - 1, 2**63 - 1, 0, -7-field][index % 5])
            insert(db, kind + "_execution_field_values", {kind + "_execution_id": execution,
                   "snapshot_field_id": f"{new}-{kind}-field-{field}", "number_scaled": value})
            expected[f"{kind}-source-{field}"].append(value)
    db.execute(f"UPDATE {kind}_templates SET deleted_at_ms=2")
    return new, expected


def rank_query(kind, pivots):
    # Expand the actual production SQL template and the same balanced binary pivot tree.
    source = (ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/GroupedNumberMedians.kt").read_text(encoding="utf-8")
    template = source[source.index("    val sql ="):source.index("    return SimpleSQLiteQuery")]
    sql = "".join(re.findall(r'"([^"\n]*)"', template))
    values = {field.encode("utf-8").hex(): pivot for field, pivot in pivots.items()}
    keys = sorted(values)

    def expression(start, end):
        if end-start == 1:
            return str(values[keys[start]])
        middle = (start+end)//2
        return (f"CASE WHEN fields.source_field_id COLLATE BINARY < CAST(X'{keys[middle]}' AS TEXT) "
                f"THEN {expression(start, middle)} ELSE {expression(middle, end)} END")

    eligible = ("executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL" if kind == "activity" else
                "executions.status IN ('COMPLETED', 'ENDED_EARLY')")
    ids = ",".join(f"CAST(X'{key}' AS TEXT)" for key in keys)
    return sql.replace("${table}", kind).replace("$eligible", eligible).replace("$ids", ids).replace("$pivot", expression(0, len(keys)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-only", action="store_true")
    args = parser.parse_args()
    for kind in ("activity", "sequence"):
        corrected_work = []
        for fields in (4, 8, 16, 24):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "shared.db"
                with closing(sqlite3.connect(path)) as db:
                    schema(db)
                    series, expected = shared_fixture(db, kind, fields)
                    db.commit()
                with closing(sqlite3.connect(path)) as db:
                    db.row_factory = sqlite3.Row
                    bindings = dict(seriesId=series, startDate=None, endDate=None)
                    summaries = db.execute(query(kind + "NumberSummaries"), bindings).fetchall()
                    candidates = db.execute(query(kind + "NumberExecutionCandidateCount"), bindings).fetchone()[0]
                    baseline = 0
                    for row in summaries:
                        params = dict(bindings, fieldId=row["source_field_id"], expectedCount=row["sample_count"])
                        cap = dict(fieldId=row["source_field_id"], limit=2*candidates+1)
                        assert db.execute(query(kind + "NumberFieldCandidateCount"), cap).fetchone()[0] == cap["limit"]
                        baseline += measured(db, query(kind + "NumberFieldCandidateCount"), cap)
                        sql = query(kind + "SeriesNumberSelection")
                        baseline += measured(db, sql, params)
                        selection = db.execute(sql, params).fetchone()
                        assert distribution([row], selection) == reference(expected[row["source_field_id"]].copy())
                    if args.baseline_only:
                        statements = []
                        db.set_trace_callback(statements.append)
                        for row in summaries:
                            params = dict(bindings, fieldId=row["source_field_id"], expectedCount=row["sample_count"])
                            db.execute(query(kind + "SeriesNumberSelection"), params).fetchone()
                        db.set_trace_callback(None)
                        print(kind, fields, "baseline VM", baseline, "full scans", len(statements))
                        assert len(statements) <= 1, "F1: executed P full selected execution/value scans"
                    cap = dict(fieldId=summaries[0]["source_field_id"], limit=2*candidates+1)
                    work = measured(db, query(kind + "NumberFieldCandidateCount"), cap)
                    searches = {r["source_field_id"]: [r["minimum_scaled"], r["maximum_scaled"]] for r in summaries}
                    counts = {r["source_field_id"]: r["sample_count"] for r in summaries}
                    selected = {}
                    passes = 0
                    first_plan = None
                    while searches:
                        passes += 1
                        assert passes <= 65
                        pivots = {field: (bounds[0]+bounds[1])//2 for field, bounds in searches.items()}
                        sql = rank_query(kind, pivots)
                        params = (series, None, None, None)
                        details = plan(db, sql, params)
                        first_plan = first_plan or details
                        paths = [line for line in details if "SEARCH " in line.upper()]
                        assert len(paths) == 3 and "executions" in paths[0], details
                        assert not any("CORRELATED" in line.upper() for line in details), details
                        work += measured(db, sql, params)
                        ranks = db.execute(sql, params).fetchall()
                        assert len(ranks) == len(pivots)  # Scalar rows bounded by result dimensions.
                        for row in ranks:
                            field = row["source_field_id"]
                            count, below = counts[field], row["at_or_below_count"]
                            low, high = searches[field]
                            assert row["sample_count"] == count
                            if below == (count-1)//2+1:
                                a = row["maximum_at_or_below"]
                                b = row["minimum_above"] if count % 2 == 0 else a
                            elif low == high:
                                assert below > (count-1)//2
                                a = b = low
                            else:
                                if below <= (count-1)//2:
                                    searches[field][0] = row["minimum_above"]
                                else:
                                    searches[field][1] = row["maximum_at_or_below"]
                                continue
                            selected[field] = dict(sample_count=count, selected_count=2-count % 2,
                                                   lower_median_scaled=a, upper_median_scaled=b)
                            del searches[field]
                    for row in summaries:
                        assert distribution([row], selected[row["source_field_id"]]) == reference(expected[row["source_field_id"]].copy())
                    repository = ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt"
                    if "INDEXED_NUMBER_SORT_WORK" in repository.read_text(encoding="utf-8"):
                        from statistics_lineage_boundary_probe import strategy
                        selected, work, shape = strategy(db, kind, series, summaries)
                        assert shape == (2 * fields, 0, 3, 0), shape
                        for row in summaries:
                            assert distribution([row], selected[row["source_field_id"]]) == reference(expected[row["source_field_id"]].copy())
                    if fields == 24:
                        assert work < baseline
                    corrected_work.append(work)
                    print(kind, fields, "baseline VM", baseline, "grouped VM", work, "passes", passes, "plans", first_plan)
        # Six times as many Fields includes logarithmic lookup cost, well below 36 times.
        assert corrected_work[-1] < 16 * corrected_work[0], corrected_work
    print("PASS shared lineage exactness, physical reopen, shared passes and subquadratic work scaling")


if __name__ == "__main__":
    main()
