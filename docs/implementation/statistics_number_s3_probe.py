"""S3 exact grouped Number probe on v10; host plans are not Android execution evidence."""

import argparse
import json
import re
import sqlite3
from fractions import Fraction
from statistics_duration_probe import SCHEMA, DAO, ROOT, query, reference
from statistics_number_probe import insert


def fixture(db, kind, rows, fields):
    series = kind + "-series"
    insert(db, "statistics_series", dict(id=series, kind=kind.upper(), display_name=kind, created_at_ms=0))
    template = dict(id=kind, name=kind, revision=1, statistics_series_id=series, created_at_ms=0, updated_at_ms=0)
    if kind == "activity":
        template["time_tracking_mode"] = "NO_LIVE_TRACKING"
    insert(db, kind + "_templates", template)
    snapshot = dict(id=kind + "-snapshot", name=kind, statistics_series_id=series, created_at_ms=0)
    if kind == "activity":
        snapshot["time_tracking_mode"] = "NO_LIVE_TRACKING"
    insert(db, kind + "_snapshots", snapshot)
    for field in range(fields + 1):  # Last configured Field deliberately has no samples.
        insert(db, kind + "_template_fields", dict(
            id=f"{kind}-source-{field}", **{kind + "_template_id": kind}, position=field,
            name="Same label", field_type="NUMBER", created_at_ms=0, updated_at_ms=0))
        insert(db, kind + "_snapshot_fields", dict(
            id=f"{kind}-field-{field}",
            **{("snapshot_id" if kind == "activity" else "sequence_snapshot_id"): kind + "-snapshot"},
            source_field_id=f"{kind}-source-{field}", position=field, name_at_creation="Same label", field_type="NUMBER"))
    samples = {f"{kind}-source-{f}": [] for f in range(fields + 1)}
    for i in range(rows):
        date = "2026-08-20" if i % 3 else "2026-07-01"
        execution = dict(id=f"{kind}-{i}", snapshot_id=kind + "-snapshot", statistics_series_id=series,
                         status="COMPLETED", original_zone_id="UTC", primary_local_date=date,
                         created_at_ms=0, updated_at_ms=1)
        if kind == "activity":
            execution.update(context_type="STANDALONE", completed_at_ms=1)
        else:
            execution.update(started_at_ms=0, ended_at_ms=1, active_duration_ms=1, pause_duration_ms=0, wall_duration_ms=1)
            if i % 2:
                execution["status"] = "ENDED_EARLY"
        insert(db, kind + "_executions", execution)
        for f in range(fields):
            value = [-(2**63), 2**63 - 1, 0, -7, i * 1000000007, -i * 1000000009, 7][(i + f) % 7]
            if i % 5 != f % 5:
                insert(db, kind + "_execution_field_values", {
                    kind + "_execution_id": execution["id"], "snapshot_field_id": f"{kind}-field-{f}", "number_scaled": value})
                samples[f"{kind}-source-{f}"].append((date, value))
    return series, samples


def series_selection_sql(db, kind, bindings, history_count):
    """Mirror the production cardinality policy; scalar preflight never returns IDs."""
    count = bindings["expectedCount"]
    if count and count >= history_count // 2 + history_count % 2:
        lineage = db.execute(query(kind + "NumberFieldLineageCount"),
                             dict(fieldId=bindings["fieldId"], limit=count + 1)).fetchone()[0]
        if lineage <= count:
            return query(kind + "DenseSeriesNumberSelection"), 1
        return query(kind + "SeriesNumberSelection"), 1
    return query(kind + "SeriesNumberSelection"), 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=10000)
    parser.add_argument("--fields", type=int, default=4)
    args = parser.parse_args()
    assert args.rows > 0 and args.fields > 0
    with sqlite3.connect(":memory:") as db:
        db.row_factory = sqlite3.Row
        for entity in json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]["entities"]:
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]) +
                           (" WHERE is_main_value = 1" if "main" in index["name"] else ""))
        db.execute("PRAGMA foreign_keys = ON")
        print("Host SQLite", sqlite3.sqlite_version, "v10; executions/kind", args.rows, "populated Fields", args.fields)
        for kind in ("activity", "sequence"):
            series, samples = fixture(db, kind, args.rows, args.fields)
            try:
                summary_sql = query(kind + "NumberSummaries")
            except (AssertionError, ValueError):
                summary_sql = query(kind + "NumberSamples")  # Fail-first boundary against pre-S3 SQL.
            for date in (None, "2026-08-20", "2027-01-01"):
                bindings = dict(seriesId=series, startDate=date, endDate=date)
                # Count actual placeholder occurrences as Room expands repeated named arguments.
                assert len(re.findall(r":\w+", summary_sql)) == 4
                assert len(re.findall(r":\w+", query(kind + "SeriesNumberSelection"))) == 9
                statements = []
                db.set_trace_callback(statements.append)
                summaries = db.execute(summary_sql, bindings).fetchall()
                assert len(summaries) <= args.fields, f"unbounded raw Number rows: {len(summaries)} > F={args.fields}"
                assert len({r["source_field_id"] for r in summaries}) == len(summaries)
                history_count = db.execute(query(kind + "HistoryExecutionCount")).fetchone()[0] if summaries else 0
                lineage_reads = 0
                for row in summaries:
                    count = row["sample_count"]
                    bits = [int(v) for v in row["bit_counts"].split(",")]
                    assert len(bits) == 64 and all(0 <= bit <= count for bit in bits) and row["invalid_count"] == 0
                    total = sum(v * (-(1 << 63) if b == 63 else 1 << b) for b, v in enumerate(bits))
                    params = dict(bindings, fieldId=row["source_field_id"], expectedCount=count)
                    median_sql, preflights = series_selection_sql(db, kind, params, history_count)
                    lineage_reads += preflights
                    selections = db.execute(median_sql, params).fetchall()
                    assert len(selections) == 1
                    selected = selections[0]
                    assert selected["sample_count"] == count and selected["selected_count"] == 2 - count % 2
                    actual = (count, total, Fraction(total, count),
                              Fraction(selected["lower_median_scaled"] + selected["upper_median_scaled"], 2),
                              row["minimum_scaled"], row["maximum_scaled"])
                    expected = reference([v for day, v in samples[row["source_field_id"]] if date is None or day == date])
                    assert actual == expected
                db.set_trace_callback(None)
                expected_fields = {f for f, values in samples.items() if any(date is None or day == date for day, _ in values)}
                assert {r["source_field_id"] for r in summaries} == expected_fields
                # Summary + one history size + P medians + <=P capped lineage checks.
                assert len(statements) == 1 + bool(summaries) + len(summaries) + lineage_reads
                print(kind, date or "AllTime", "summary_rows", len(summaries), "median_rows", len(summaries),
                      "queries", len(statements), "PASS exact integer/Fraction reference")
                if date != "2027-01-01":
                    for label, sql in (("grouped", summary_sql), ("median", query(kind + "SeriesNumberSelection"))):
                        print(kind, date or "AllTime", label, "EXPLAIN QUERY PLAN")
                        params = dict(bindings, fieldId=kind + "-source-0", expectedCount=1)
                        for plan in db.execute("EXPLAIN QUERY PLAN " + sql, params):
                            print(" ", plan[3])
            for literal in ("0.5", "'corrupt'"):
                db.execute(f"UPDATE {kind}_execution_field_values SET number_scaled = {literal}")
                bad = db.execute(summary_sql, dict(seriesId=series, startDate=None, endDate=None)).fetchall()
                assert all(row["invalid_count"] > 0 for row in bad)
        dao_source = DAO.read_text(encoding="utf-8")
        repository_source = (ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt").read_text(encoding="utf-8")
        assert "StatisticsNumberSampleRow" not in dao_source
        assert "NumberSamples(" not in dao_source and "NumberSamples(" not in repository_source
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        print("PASS: F summary rows, 64F scalar counts, <=F scalar medians and <=F capped lineage checks; database work grows with history.")


if __name__ == "__main__":
    main()
