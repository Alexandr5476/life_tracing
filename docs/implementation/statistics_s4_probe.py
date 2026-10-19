"""S4 consolidated host SQLite comparison; never Android latency/heap evidence."""

import argparse
import json
import sqlite3
import time
import tracemalloc
from fractions import Fraction

from statistics_duration_probe import SCHEMA, query, reference, scalar_distribution
from statistics_number_probe import insert
from statistics_number_s3_probe import fixture, series_selection_sql


def timed_read(db, sql, bindings):
    """Measure Python result retention separately from native SQLite work."""
    statements = []
    db.set_trace_callback(statements.append)
    tracemalloc.start()
    start = time.perf_counter()
    try:
        rows = db.execute(sql, bindings).fetchall()
        elapsed = (time.perf_counter() - start) * 1000
        retained, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
        db.set_trace_callback(None)
    assert len(statements) == 1
    return rows, dict(rows=len(rows), statements=len(statements), host_ms=round(elapsed, 3),
                      python_retained_bytes=retained, python_peak_bytes=peak)


def eligible(sql):
    return sql.split("eligible AS (", 1)[1].split("), median_values AS", 1)[0]


def distribution(summary, selection):
    count = summary["sample_count"]
    bits = [int(bit) for bit in summary["bit_counts"].split(",")]
    assert len(bits) == 64 and summary["invalid_count"] == 0
    assert all(0 <= bit <= count for bit in bits)
    total = sum(n * (-(1 << 63) if bit == 63 else 1 << bit) for bit, n in enumerate(bits))
    assert selection["sample_count"] == count
    assert selection["selected_count"] == 2 - count % 2
    return (count, total, Fraction(total, count),
            Fraction(selection["lower_median_scaled"] + selection["upper_median_scaled"], 2),
            summary["minimum_scaled"], summary["maximum_scaled"])


def mixed_fixture(db, rows, fields):
    # SQL-plan fixture, not a substitute for domain-validated Room lifecycle fixtures.
    # The seed supplies both Long extremes, sparse values, duplicate labels and an empty Field.
    for kind in ("sequence", "activity"):
        fixture(db, kind, rows, fields)
        db.execute(f"UPDATE {kind}_templates SET deleted_at_ms = 2")
        for i in range(rows):
            identity = f"{kind}-{i}"
            if kind == "activity":
                # Timed and No-live coexist in the queried cache. Frozen configuration is
                # not loaded by these SQL statements; full snapshot validation is Room-only.
                duration = None if i % 7 == 0 else (i % 17) * 1000
                db.execute("UPDATE activity_executions SET active_duration_ms = ?, "
                           "deleted_at_ms = ? WHERE id = ?", (duration, 2 if i % 13 == 0 else None, identity))
                if i % 11 == 0:
                    occurrence = f"probe-occurrence-{i}"
                    insert(db, "sequence_occurrences", dict(id=occurrence, sequence_execution_id=f"sequence-{i}",
                           activity_snapshot_id="activity-snapshot", runtime_position=0, status="COMPLETED"))
                    db.execute("UPDATE activity_executions SET context_type = 'SEQUENCE_CHILD', "
                               "sequence_execution_id = ?, sequence_occurrence_id = ? WHERE id = ?",
                               (f"sequence-{i}", occurrence, identity))
            else:
                duration = (i % 17) * 1000
                db.execute("UPDATE sequence_executions SET active_duration_ms = ?, ended_at_ms = ?, "
                           "wall_duration_ms = ? WHERE id = ?", (duration, duration, duration, identity))
            # A correction moves original-date membership and changes signed values.
            if i % 19 == 0:
                db.execute(f"UPDATE {kind}_executions SET primary_local_date = '2026-08-20', "
                           "updated_at_ms = 3 WHERE id = ?", (identity,))
                db.execute(f"UPDATE {kind}_execution_field_values SET number_scaled = 0 "
                           f"WHERE {kind}_execution_id = ?", (identity,))
        # First Field dense, remaining Fields sparse, final catalog Field always empty.
        for i in range(rows):
            db.execute(f"INSERT OR IGNORE INTO {kind}_execution_field_values "
                       f"({kind}_execution_id,snapshot_field_id,number_scaled) VALUES (?,?,?)",
                       (f"{kind}-{i}", f"{kind}-field-0", i - rows // 2))
        insert(db, kind + "_template_fields", dict(id=kind + "-category", **{kind + "_template_id": kind},
               position=fields + 1, name="Choice", field_type="CATEGORY", created_at_ms=0, updated_at_ms=0))
        insert(db, kind + "_template_category_options", dict(id=kind + "-option",
               **{kind + "_template_field_id": kind + "-category"}, position=0, label="Choice"))
        insert(db, kind + "_snapshot_fields", dict(id=kind + "-category-snapshot",
               **{("snapshot_id" if kind == "activity" else "sequence_snapshot_id"): kind + "-snapshot"},
               source_field_id=kind + "-category", position=fields + 1, name_at_creation="Choice", field_type="CATEGORY"))
        insert(db, kind + "_snapshot_category_options", dict(id=kind + "-option-snapshot",
               **{("snapshot_field_id" if kind == "activity" else "sequence_snapshot_field_id"): kind + "-category-snapshot"},
               source_option_id=kind + "-option", position=0, label_at_creation="Choice"))
        for i in range(0, rows, 2):
            insert(db, kind + "_execution_field_values", {kind + "_execution_id": f"{kind}-{i}",
                   "snapshot_field_id": kind + "-category-snapshot", "category_option_id": kind + "-option-snapshot"})
        # Model growing frozen source-Field lineage, rather than making every execution
        # share one snapshot. A split Series shares source identities but not samples.
        other_series = kind + "-other-series"
        insert(db, "statistics_series", dict(id=other_series, kind=kind.upper(),
               display_name="Archived split", created_at_ms=0))
        frozen_fields = [dict(row) for row in db.execute(f"SELECT * FROM {kind}_snapshot_fields")]
        frozen_options = [dict(row) for row in db.execute(f"SELECT * FROM {kind}_snapshot_category_options")]
        owner_column = "snapshot_id" if kind == "activity" else "sequence_snapshot_id"
        option_owner = "snapshot_field_id" if kind == "activity" else "sequence_snapshot_field_id"
        for i in range(rows):
            identity = f"{kind}-{i}"
            snapshot_id = identity + "-frozen"
            series_id = other_series if i % 17 == 0 else kind + "-series"
            if kind == "activity" and i % 77 == 0:
                series_id = None  # A true source-less Sequence child, never the one-off bucket.
            snapshot = dict(id=snapshot_id, name="Frozen", statistics_series_id=series_id, created_at_ms=0)
            if kind == "activity":
                snapshot["time_tracking_mode"] = "NO_LIVE_TRACKING" if i % 7 == 0 else "STOPWATCH"
            insert(db, kind + "_snapshots", snapshot)
            field_ids = {}
            option_ids = {}
            for original in frozen_fields:
                copied = dict(original, id=identity + "-" + original["id"], **{owner_column: snapshot_id})
                field_ids[original["id"]] = copied["id"]
                insert(db, kind + "_snapshot_fields", copied)
            for original in frozen_options:
                copied = dict(original, id=identity + "-" + original["id"],
                              **{option_owner: field_ids[original[option_owner]]})
                option_ids[original["id"]] = copied["id"]
                insert(db, kind + "_snapshot_category_options", copied)
            values = db.execute(f"SELECT snapshot_field_id,category_option_id FROM {kind}_execution_field_values "
                                f"WHERE {kind}_execution_id = ?", (identity,)).fetchall()
            for value in values:
                db.execute(f"UPDATE {kind}_execution_field_values SET snapshot_field_id = ?,category_option_id = ? "
                           f"WHERE {kind}_execution_id = ? AND snapshot_field_id = ?",
                           (field_ids[value[0]], option_ids.get(value[1]), identity, value[0]))
            db.execute(f"UPDATE {kind}_executions SET snapshot_id = ?,statistics_series_id = ? WHERE id = ?",
                       (snapshot_id, series_id, identity))
            if kind == "activity" and i % 11 == 0:
                db.execute("UPDATE sequence_occurrences SET activity_snapshot_id = ? WHERE id = ?",
                           (snapshot_id, f"probe-occurrence-{i}"))
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    db.commit()


def compare(db, kind, date, fields, plans):
    bindings = dict(seriesId=kind + "-series", startDate=date, endDate=date)
    label = kind + "/" + (date or "AllTime")
    aggregate_sql = query(kind + "SeriesAggregates")
    duration_sql = query(kind + "DurationSelection")
    aggregates = db.execute(aggregate_sql, bindings).fetchall()
    assert len(aggregates) <= 1
    aggregate = aggregates[0] if aggregates else None
    count = aggregate["duration_sample_count"] if aggregate else 0
    params = dict(bindings, expectedCount=count)
    baseline, old = timed_read(db, eligible(duration_sql), params)
    selected, new = timed_read(db, duration_sql, params)
    assert len(selected) == 1
    assert scalar_distribution(aggregate, selected[0]) == reference([r[0] for r in baseline])
    print(label, "duration", json.dumps(dict(matching_executions=aggregate["execution_count"] if aggregate else 0,
          samples=count, baseline=old, optimized=new, median_endpoints=selected[0]["selected_count"])))
    grouped_sql = query(kind + "NumberSummaries")
    grouped, grouped_metrics = timed_read(db, grouped_sql, bindings)
    assert len(grouped) <= fields
    median_rows = 0
    number_samples = 0
    execution_candidates = db.execute(query(kind + "NumberExecutionCandidateCount"), bindings).fetchone()[0] if grouped else 0
    lineage_reads = 0
    for field in range(fields + 1):
        field_id = f"{kind}-source-{field}"
        params = dict(bindings, fieldId=field_id)
        individual_sql = query(kind + "NumberSelection")
        baseline, old = timed_read(db, eligible(individual_sql), params)
        expected = reference([r[0] for r in baseline])
        bits, bit_metrics = timed_read(db, query(kind + "NumberBitCounts"), params)
        assert len(bits) == 1
        counts = [int(value) for value in bits[0]["bit_counts"].split(",")]
        assert len(counts) == 64 and bits[0]["invalid_count"] == 0
        total = sum(value * (-(1 << 63) if bit == 63 else 1 << bit) for bit, value in enumerate(counts))
        assert total == expected[1] and bits[0]["sample_count"] == expected[0]
        params["expectedCount"] = expected[0]
        selection, selection_metrics = timed_read(db, individual_sql, params)
        assert len(selection) == 1 and selection[0]["sample_count"] == expected[0]
        selected = selection[0]
        actual_median = (Fraction(selected["lower_median_scaled"] + selected["upper_median_scaled"], 2)
                         if expected[0] else None)
        assert selected["selected_count"] == (2 - expected[0] % 2 if expected[0] else 0)
        assert actual_median == expected[3]
        summaries = [row for row in grouped if row["source_field_id"] == field_id]
        if expected[0]:
            assert len(summaries) == 1
            median_sql, preflights = series_selection_sql(db, kind, params, execution_candidates)
            lineage_reads += preflights
            series_selection, series_metrics = timed_read(db, median_sql, params)
            assert len(series_selection) == 1 and distribution(summaries[0], series_selection[0]) == expected
            median_rows += 1
            number_samples += expected[0]
        else:
            assert not summaries
        print(label, field_id, json.dumps(dict(samples=expected[0], baseline=old, bits=bit_metrics,
                                              individual_selection=selection_metrics)))
    assert median_rows == len(grouped)
    print(label, "combined", json.dumps(dict(number_samples=number_samples, baseline_raw_rows=number_samples,
          summary=grouped_metrics, median_rows=median_rows, number_statements=1 + bool(grouped) + median_rows + lineage_reads)))
    category = db.execute(query(kind + "FieldCategoryCounts"), bindings).fetchall()
    assert len(category) <= 1
    if plans:
        for name in (kind + "SeriesAggregates", kind + "DurationSelection", kind + "NumberBitCounts",
                     kind + "NumberSelection", kind + "NumberSummaries", kind + "SeriesNumberSelection"):
            print(label, name, "EXPLAIN QUERY PLAN")
            for row in db.execute("EXPLAIN QUERY PLAN " + query(name), dict(bindings, fieldId=kind + "-source-0", expectedCount=count)):
                print(" ", row[3])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=3000)
    parser.add_argument("--fields", type=int, default=4)
    parser.add_argument("--plans", action="store_true")
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
        mixed_fixture(db, args.rows, args.fields)
        print("HOST ONLY SQLite", sqlite3.sqlite_version, "rows/kind", args.rows, "Fields", args.fields)
        print("One observation per query; Python allocation tracking enabled; no Android heap/native timing claim.")
        for kind in ("activity", "sequence"):
            for date in (None, "2026-08-20", "2027-01-01"):
                compare(db, kind, date, args.fields, args.plans)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        print("PASS exact baseline comparison and bounded cardinalities; native work remains history-dependent.")


if __name__ == "__main__":
    main()
