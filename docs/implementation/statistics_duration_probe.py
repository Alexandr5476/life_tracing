"""S1C1 host-only exact bounded-result SQL probe (not an Android timing benchmark)."""

import argparse
import json
import re
import sqlite3
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "data/schemas/com.alexandr5476.lifetracing.data.persistence.LifeTracingDatabase/10.json"
DAO = ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsDao.kt"


def query(name):
    source = DAO.read_text(encoding="utf-8")
    end = source.index("    fun " + name + "(")
    start = source.rindex("    @Query(", 0, end)
    return "".join(re.findall(r'"([^"\n]*)"', source[start:end]))


def reference(values):
    values.sort()
    count = len(values)
    median = None if not count else Fraction(values[(count - 1) // 2] + values[count // 2], 2)
    return (count, sum(values), Fraction(sum(values), count) if count else None,
            median, values[0] if count else None, values[-1] if count else None)


def scalar_distribution(aggregate, selection):
    count = 0 if aggregate is None else aggregate["duration_sample_count"]
    assert selection["sample_count"] == count
    expected = 0 if count == 0 else 2 - count % 2
    assert selection["selected_count"] == expected
    if count == 0:
        assert selection["lower_median_ms"] is None and selection["upper_median_ms"] is None
        return (0, 0, None, None, None, None)
    assert aggregate["invalid_duration_count"] == 0
    total = aggregate["total_duration_ms"]
    assert 0 <= total <= 2**63 - 1
    lower, upper = selection["lower_median_ms"], selection["upper_median_ms"]
    minimum, maximum = aggregate["minimum_duration_ms"], aggregate["maximum_duration_ms"]
    assert 0 <= minimum <= lower <= upper <= maximum
    return (count, total, Fraction(total, count), Fraction(lower + upper, 2), minimum, maximum)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=3000)
    parser.add_argument("--varied", action="store_true")
    args = parser.parse_args()
    assert args.rows >= 3
    with sqlite3.connect(":memory:") as db:
        db.row_factory = sqlite3.Row
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        print("Host SQLite", sqlite3.sqlite_version, "schema", schema["version"], "rows per kind", args.rows)
        for kind in ("activity", "sequence"):
            series_id = kind + "-fixture"
            db.execute("INSERT INTO statistics_series VALUES (?, ?, ?, 0, NULL)",
                       (series_id, kind.upper(), kind))
            table = kind + "_executions"
            end = "completed_at_ms" if kind == "activity" else "ended_at_ms"
            columns = ["id", "snapshot_id", "statistics_series_id", "status", "started_at_ms", end,
                       "active_duration_ms", "original_zone_id", "primary_local_date", "created_at_ms", "updated_at_ms"]
            if kind == "activity":
                columns.append("context_type")
            for i in range(args.rows):
                duration = (i % 17) * 1000 if args.varied else 1000
                row = [str(i), "snapshot", series_id, "COMPLETED", 0, duration, duration, "UTC",
                       "2026-08-20" if i < args.rows * 2 // 3 else "2026-07-01", 0, duration]
                if kind == "activity":
                    row.append("STANDALONE")
                db.execute(f"INSERT INTO {table} ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})", row)
            selection_sql = query(kind + "DurationSelection")
            eligible_sql = selection_sql.split("WITH eligible AS (", 1)[1].split("), median_values AS", 1)[0]
            aggregate_sql = query(kind + "SeriesAggregates")
            for start in (None, "2026-08-20"):
                bindings = {"seriesId": series_id, "startDate": start, "endDate": start}
                statements = []
                db.set_trace_callback(statements.append)
                rows = db.execute(aggregate_sql, bindings).fetchall()
                assert len(rows) <= 1
                aggregate = rows[0] if rows else None
                count = 0 if aggregate is None else aggregate["duration_sample_count"]
                bindings["expectedCount"] = count
                selected = db.execute(selection_sql, bindings).fetchall()
                assert len(statements) == 2 and len(selected) == 1
                db.set_trace_callback(None)
                actual = scalar_distribution(aggregate, selected[0])
                baseline = [row[0] for row in db.execute(eligible_sql, bindings)]
                expected = reference(baseline)
                assert actual == expected
                print(kind, start or "AllTime", "execution_count=", aggregate["execution_count"],
                      "duration_count=", count, "baseline_returned_rows=", len(baseline),
                      "new_aggregate_rows=", len(rows), "new_selection_rows=", len(selected),
                      "median_values=", selected[0]["selected_count"], "duration_DAO_calls=2", "result=", actual)
                # A wrong or absent aggregate cannot conceal a nonempty eligible duration set.
                wrong = db.execute(selection_sql, dict(bindings, expectedCount=0)).fetchone()
                assert wrong["sample_count"] == count and wrong["selected_count"] == 0
                for label, sql in [("eligible", eligible_sql), ("aggregate", aggregate_sql), ("median", selection_sql)]:
                    print(" ", label)
                    for plan in db.execute("EXPLAIN QUERY PLAN " + sql, bindings):
                        print("   ", plan[3])
        maximum = 2**63 - 1
        assert db.execute("SELECT SUM(value) FROM (SELECT ? AS value UNION ALL SELECT 0)", (maximum,)).fetchone()[0] == maximum
        try:
            db.execute("SELECT SUM(value) FROM (SELECT ? AS value UNION ALL SELECT 1)", (maximum,)).fetchone()
        except sqlite3.OperationalError as error:
            assert "integer overflow" in str(error)
        else:
            raise AssertionError("Integral SUM failed to reject duration overflow")
        print("Integer SUM boundary/overflow: PASS. No Android timings, heap or CursorWindow refill counts measured.")
        print("Canonical reader: catalog + aggregate + bounded selection = 3 application SELECTs.")


if __name__ == "__main__":
    main()
