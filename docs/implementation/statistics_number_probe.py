"""S2 host SQLite exact Number/query-shape probe; not Android timing evidence."""

import argparse
import json
import sqlite3
from fractions import Fraction
from statistics_duration_probe import SCHEMA, query, reference


def insert(db, table, values):
    # Explicit minimal fixture, with no dependence on other tests or application behavior.
    columns = ",".join(values)
    placeholders = ",".join("?" for _ in values)
    db.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", tuple(values.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=10000)
    args = parser.parse_args()
    assert args.rows > 0
    with sqlite3.connect(":memory:") as db:
        db.row_factory = sqlite3.Row
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        # Foreign keys are checked, including source Field and snapshot provenance.
        db.execute("PRAGMA foreign_keys = ON")
        print("Host SQLite", sqlite3.sqlite_version, "v10 indexes; rows/kind", args.rows)
        for kind in ("activity", "sequence"):
            series = kind + "-series"
            insert(db, "statistics_series", dict(id=series, kind=kind.upper(), display_name=kind, created_at_ms=0))
            template = dict(id=kind, name=kind, revision=1, statistics_series_id=series, created_at_ms=0, updated_at_ms=0)
            if kind == "activity":
                template["time_tracking_mode"] = "NO_LIVE_TRACKING"
            insert(db, kind + "_templates", template)
            insert(db, kind + "_template_fields", dict(
                id=kind + "-source", **{kind + "_template_id": kind}, position=0, name="Same label",
                field_type="NUMBER", created_at_ms=0, updated_at_ms=0))
            snapshot = dict(id=kind + "-snapshot", name=kind, statistics_series_id=series, created_at_ms=0)
            if kind == "activity":
                snapshot["time_tracking_mode"] = "NO_LIVE_TRACKING"
            insert(db, kind + "_snapshots", snapshot)
            insert(db, kind + "_snapshot_fields", dict(
                id=kind + "-field", **{("snapshot_id" if kind == "activity" else "sequence_snapshot_id"): kind + "-snapshot"},
                source_field_id=kind + "-source", position=0, name_at_creation="Same label", field_type="NUMBER"))
            samples = []
            maximum = 2**63 - 1
            minimum = -(2**63)
            for i in range(args.rows):
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
                value = [minimum, maximum, 0, -7, i * 1000000007, -i * 1000000009, 7][i % 7]
                if i % 5 != 4:
                    insert(db, kind + "_execution_field_values", {
                        kind + "_execution_id": execution["id"], "snapshot_field_id": kind + "-field", "number_scaled": value})
                    samples.append((date, value))
            summary_sql = query(kind + "NumberBitCounts")
            selection_sql = query(kind + "NumberSelection")
            count_sql = query(kind + "NumberExecutionCount")
            for date in (None, "2026-08-20", "2027-01-01"):
                bindings = dict(seriesId=series, fieldId=kind + "-source", startDate=date, endDate=date)
                statements = []
                db.set_trace_callback(statements.append)
                denominator = db.execute(count_sql, bindings).fetchone()[0]
                bits = db.execute(summary_sql, bindings).fetchall()
                assert len(bits) == 1
                row = bits[0]
                count = row["sample_count"]
                counts = [int(value) for value in row["bit_counts"].split(",")]
                assert len(counts) == 64 and row["invalid_count"] == 0
                assert all(0 <= value <= count for value in counts)
                total = sum(value * (-(1 << 63) if bit == 63 else 1 << bit) for bit, value in enumerate(counts))
                bindings["expectedCount"] = count
                selections = db.execute(selection_sql, bindings).fetchall()
                assert len(statements) == 3 and len(selections) == 1
                db.set_trace_callback(None)
                selected = selections[0]
                assert selected["sample_count"] == count and selected["selected_count"] == (0 if not count else 2 - count % 2)
                actual = (count, total, Fraction(total, count) if count else None,
                          Fraction(selected["lower_median_scaled"] + selected["upper_median_scaled"], 2) if count else None,
                          bits[0]["minimum_scaled"], bits[0]["maximum_scaled"])
                expected = reference([value for day, value in samples if date is None or day == date])
                assert actual == expected
                assert denominator >= count
                print(kind, date or "AllTime", "relevant", denominator, "recorded", count,
                      "summary_rows", len(bits), "selection_rows", len(selections), "queries", len(statements), "exact", actual)
                wrong = db.execute(selection_sql, dict(bindings, expectedCount=0)).fetchone()
                assert wrong["sample_count"] == count and wrong["selected_count"] == 0
                if date is None:
                    for label, sql in (("count", count_sql), ("bits", summary_sql), ("median", selection_sql)):
                        print(kind, label, "EXPLAIN QUERY PLAN")
                        for row in db.execute("EXPLAIN QUERY PLAN " + sql, bindings):
                            print(" ", row[3])
            # Isolated corruption probe: integer affinity must not silently narrow a REAL or TEXT.
            for literal in ("0.5", "'corrupt'"):
                db.execute(f"UPDATE {kind}_execution_field_values SET number_scaled = {literal}")
                rows = db.execute(summary_sql, dict(seriesId=series, fieldId=kind + "-source", startDate=None, endDate=None)).fetchall()
                assert all(row["invalid_count"] > 0 for row in rows)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        print("PASS: exact Python integer/Fraction reference, bounded 1+1 result rows, 3 numeric queries.")
        print("Canonical Room reader adds catalog+metadata: 5 reads. No device latency/heap/refill measurements.")


if __name__ == "__main__":
    main()
