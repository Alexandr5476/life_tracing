"""PR37 B1/W1 fail-first plans and repeatable host comparisons; no latency gate.

Uses production annotation SQL and the v10 schema/indexes. Host SQLite evidence
does not replace the mandatory five-API Room instrumentation gate.
"""

import argparse
from contextlib import closing
import json
import re
import sqlite3
import subprocess
import tempfile
from pathlib import Path
from time import perf_counter

from statistics_duration_probe import DAO, ROOT, SCHEMA, query, reference
from statistics_number_probe import insert
from statistics_number_s3_probe import fixture


def extract(source, name):
    end = source.index("    fun " + name + "(")
    start = source.rindex("    @Query(", 0, end)
    return "".join(re.findall(r'"([^"\n]*)"', source[start:end]))


def schema(db):
    db.row_factory = sqlite3.Row
    for entity in json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]) +
                       (" WHERE is_main_value = 1" if "main" in index["name"] else ""))
    db.execute("PRAGMA foreign_keys = ON")


def plan(db, sql, bindings):
    return [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, bindings)]


def structural(db, kind, bindings, finding, read=query):
    if finding in ("B1", "both"):
        sql = read(kind + "NumberBitCounts")
        details = plan(db, sql, bindings)
        assert not any("CORRELATED" in line.upper() for line in details), details
        assert len(re.findall(r"COUNT\(CASE WHEN \(\(", sql)) == 64, "B1: require one fixed-width aggregate"
        assert "GROUP BY" not in sql.upper(), "B1: individual read must aggregate only the requested Field"
        assert len(db.execute(sql, bindings).fetchall()) == 1
        print(kind, "B1", *details, sep="\n  ")
    if finding in ("W1", "both"):
        for suffix in ("SeriesNumberSelection", "NumberSelection"):
            details = plan(db, read(kind + suffix), bindings)
            searches = [line for line in details if "SEARCH " in line.upper()]
            assert searches and "executions" in searches[0], ("W1: history must start with eligible executions", details)
            assert not any("source_field_id=?" in line for line in searches), details
            print(kind, suffix, *details, sep="\n  ")
        dense = plan(db, read(kind + "DenseSeriesNumberSelection"), bindings)
        assert "fields" in next(line for line in dense if "SEARCH " in line.upper()), dense
        assert any("execution_values_snapshot_field_id" in line for line in dense), dense
        print(kind, "guarded dense median", *dense, sep="\n  ")
        size_sql = read(kind + "HistoryExecutionCount")
        bytecode = db.execute("EXPLAIN " + size_sql).fetchall()
        assert any(row[1] == "Count" for row in bytecode), "History size must use SQLite's B-tree COUNT"
        cap_sql = read(kind + "NumberFieldLineageCount")
        cap_bindings = dict(bindings, limit=1)
        cap_plan = plan(db, cap_sql, cap_bindings)
        assert "LIMIT :limit" in cap_sql and any("source_field_id" in line for line in cap_plan)
        assert db.execute(cap_sql, cap_bindings).fetchone()[0] <= 1
        print(kind, "capped Field metadata", *cap_plan, sep="\n  ")


def distribution(rows, selected):
    from fractions import Fraction

    row = rows[0]
    count = row["sample_count"]
    if "bit_counts" in row.keys():
        assert len(rows) == 1
        bits = [int(value) for value in row["bit_counts"].split(",")]
    else:  # Frozen reviewed baseline, deliberately retains the old query for comparison.
        assert len(rows) == 64
        bits = [value["set_bit_count"] for value in rows]
    assert len(bits) == 64 and all(0 <= bit <= count for bit in bits)
    assert row["invalid_count"] == 0
    assert selected["sample_count"] == count
    assert selected["selected_count"] == (2 - count % 2 if count else 0)
    total = sum(value * (-(1 << 63) if bit == 63 else 1 << bit) for bit, value in enumerate(bits))
    return (count, total, Fraction(total, count) if count else None,
            Fraction(selected["lower_median_scaled"] + selected["upper_median_scaled"], 2) if count else None,
            row["minimum_scaled"], row["maximum_scaled"])


def measure(db, sql, bindings, repeats):
    elapsed = []
    instructions = []
    for _ in range(repeats):
        steps = 0

        def progress():
            nonlocal steps
            steps += 1
            return 0

        db.set_progress_handler(progress, 1000)
        started = perf_counter()
        rows = db.execute(sql, bindings).fetchall()
        elapsed.append(round((perf_counter() - started) * 1000, 3))
        db.set_progress_handler(None, 0)
        instructions.append(steps * 1000)
    return rows, elapsed, instructions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=10000)
    parser.add_argument("--fields", type=int, default=4)
    parser.add_argument("--kind", choices=("activity", "sequence", "both"), default="both")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--finding", choices=("B1", "W1", "both"), default="both")
    parser.add_argument("--structural-only", action="store_true")
    parser.add_argument("--query-ref", help="Run structural regressions against SQL from this Git revision")
    parser.add_argument("--baseline-ref", default="891dfae408bf046b843accf737560df7078bde3e")
    args = parser.parse_args()
    kinds = ("activity", "sequence") if args.kind == "both" else (args.kind,)
    assert args.rows >= 4 and args.fields > 0 and args.repeats > 0
    baseline = subprocess.check_output(
        ["git", "show", args.baseline_ref + ":" + DAO.relative_to(ROOT).as_posix()], cwd=ROOT).decode("utf-8")
    inspected = query if args.query_ref is None else lambda name: extract(subprocess.check_output(
        ["git", "show", args.query_ref + ":" + DAO.relative_to(ROOT).as_posix()], cwd=ROOT).decode("utf-8"), name)
    print("Host SQLite", sqlite3.sqlite_version, "rows/kind", args.rows, "Fields", args.fields)
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "statistics.db"
        with closing(sqlite3.connect(path)) as db:
            schema(db)
            for kind in kinds:
                series, _ = fixture(db, kind, args.rows, args.fields)
                bindings = dict(seriesId=series, fieldId=kind + "-source-0", startDate=None, endDate=None, expectedCount=1)
                structural(db, kind, bindings, args.finding, inspected)
                # New Series shares every source Field identity with the old lineage.
                tiny = kind + "-tiny"
                insert(db, "statistics_series", dict(id=tiny, kind=kind.upper(), display_name=tiny, created_at_ms=0))
                db.execute(f"UPDATE {kind}_executions SET statistics_series_id = ? WHERE id IN (?, ?, ?)",
                           (tiny, kind + "-0", kind + "-1", kind + "-2"))
                # A narrow day has just one execution inside the large old lineage.
                db.execute(f"UPDATE {kind}_executions SET primary_local_date='2026-08-21' WHERE id=?", (kind + "-3",))
            db.commit()
            db.close()
        if args.structural_only:
            print("PASS structural query-plan regressions")
            return
        # Independent expectations survive a physical connection close/reopen.
        with closing(sqlite3.connect(path)) as db:
            db.row_factory = sqlite3.Row
            for kind in kinds:
                for label, series, date, indexes in (
                    ("dense-old", kind + "-series", None, range(3, args.rows)),
                    ("tiny-new", kind + "-tiny", None, range(3)),
                    ("narrow-old", kind + "-series", "2026-08-21", [3]),
                    ("empty", kind + "-series", "2027-01-01", []),
                ):
                    values = []
                    for i in indexes:
                        if i % 5 != 0:
                            values.append([-(2**63), 2**63 - 1, 0, -7, i * 1000000007, -i * 1000000009, 7][i % 7])
                    bindings = dict(seriesId=series, fieldId=kind + "-source-0", startDate=date, endDate=date,
                                    expectedCount=len(values))
                    for version, read in (("baseline", lambda name: extract(baseline, name)), ("optimized", query)):
                        summary, ms, steps = measure(db, read(kind + "NumberBitCounts"), bindings, args.repeats)
                        print(kind, label, version, "summary", "rows", len(summary), "ms", ms, "VM~", steps)
                        for suffix in ("NumberSelection", "SeriesNumberSelection"):
                            method = kind + suffix
                            if version == "optimized" and suffix == "SeriesNumberSelection" and values:
                                size_rows, size_ms, size_steps = measure(
                                    db, query(kind + "HistoryExecutionCount"), bindings, args.repeats)
                                history = size_rows[0][0]
                                print(kind, label, "history-size preflight", "rows", len(size_rows),
                                      "ms", size_ms, "VM~", size_steps)
                                if len(values) >= history // 2 + history % 2:
                                    lineage_rows, lineage_ms, lineage_steps = measure(
                                        db, query(kind + "NumberFieldLineageCount"),
                                        dict(fieldId=bindings["fieldId"], limit=len(values) + 1), args.repeats)
                                    if lineage_rows[0][0] <= len(values):
                                        method = kind + "DenseSeriesNumberSelection"
                                    print(kind, label, "capped-lineage preflight", "rows", len(lineage_rows),
                                          "ms", lineage_ms, "VM~", lineage_steps)
                                assert ("Dense" in method) == (label == "dense-old")
                                print(kind, label, "strategy", method)
                            selected, ms, steps = measure(db, read(method), bindings, args.repeats)
                            assert len(selected) == 1
                            assert distribution(summary, selected[0]) == reference(values.copy())
                            print(kind, label, version, suffix, "rows", len(selected), "ms", ms, "VM~", steps)
                for literal in ("0.5", "'corrupt'"):
                    db.execute(f"UPDATE {kind}_execution_field_values SET number_scaled = {literal}")
                    rows = db.execute(query(kind + "NumberBitCounts"),
                                      dict(seriesId=kind + "-series", fieldId=kind + "-source-0",
                                           startDate=None, endDate=None)).fetchall()
                    assert rows[0]["invalid_count"] > 0
            assert not db.execute("PRAGMA foreign_key_check").fetchall()
            db.close()
    print("PASS exact distributions, physical reopen, fixed results and plans; no wall-clock threshold")


if __name__ == "__main__":
    main()
