"""Host-only duration SQL baseline/comparison; no Android runtime or timing claim.

Run: python docs/implementation/statistics_duration_probe.py
Uses the two duration tables and all their indexes from the checked-in v10 export.
Foreign keys/lifecycle triggers are outside this isolated query-plan fixture.
"""

import json
import re
import sqlite3
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "data/schemas/com.alexandr5476.lifetracing.data.persistence.LifeTracingDatabase/10.json"
DAO = ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsDao.kt"


def duration_sql(kind):
    source = DAO.read_text(encoding="utf-8")
    end = source.index("    fun " + kind + "DurationValues(")
    start = source.rindex("    @Query(", 0, end)
    return "".join(re.findall(r'"([^"\n]*)"', source[start:end]))


def distribution(samples, count):
    total = observed = 0
    minimum = maximum = lower = upper = None
    for value in samples:
        assert value >= 0 and (maximum is None or value >= maximum)
        if observed == 0:
            minimum = value
        if observed == (count - 1) // 2:
            lower = value
        if observed == count // 2:
            upper = value
        maximum = value
        total += value
        observed += 1
    assert observed == count
    return (count, total, Fraction(total, count) if count else None,
            Fraction(lower + upper, 2) if count else None, minimum, maximum)


def main():
    with sqlite3.connect(":memory:") as db:
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        for entity in schema["entities"]:
            if entity["tableName"] not in ("activity_executions", "sequence_executions"):
                continue
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity["indices"]:
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        print("Host SQLite", sqlite3.sqlite_version, "schema", schema["version"])
        for kind in ("activity", "sequence"):
            table = kind + "_executions"
            end = "completed_at_ms" if kind == "activity" else "ended_at_ms"
            columns = ["id", "snapshot_id", "statistics_series_id", "status", "started_at_ms", end,
                       "active_duration_ms", "original_zone_id", "primary_local_date", "created_at_ms", "updated_at_ms"]
            if kind == "activity":
                columns.append("context_type")
            for i in range(3000):
                row = [str(i), "snapshot", "fixture", "COMPLETED", 0, 1000, 1000, "UTC",
                       "2026-08-20" if i < 2000 else "2026-07-01", 0, 1000]
                if kind == "activity":
                    row.append("STANDALONE")
                db.execute(f"INSERT INTO {table} ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})", row)
            sql = duration_sql(kind)
            for start in (None, "2026-08-20"):
                args = {"seriesId": "fixture", "startDate": start, "endDate": start}
                count_sql = sql.replace("SELECT active_duration_ms", "SELECT COUNT(*), COUNT(active_duration_ms)")
                count_sql = count_sql.replace(" AND active_duration_ms IS NOT NULL", "").split(" ORDER BY")[0]
                matching, nonnull = db.execute(count_sql, args).fetchone()
                statements = []
                db.set_trace_callback(statements.append)
                values = [row[0] for row in db.execute(sql, args)]
                baseline = distribution(sorted(values), nonnull)
                assert len(statements) == 1
                statements.clear()
                compared = distribution((row[0] for row in db.execute(sql, args)), nonnull)
                assert len(statements) == 1
                db.set_trace_callback(None)
                assert baseline == compared == (nonnull, nonnull * 1000, Fraction(1000), Fraction(1000), 1000, 1000)
                print(kind, start or "AllTime", "matching=", matching, "nonnull=", nonnull,
                      "baseline_list=", len(values), "comparison_sample_slots<=5", "distribution=", compared)
                for plan in db.execute("EXPLAIN QUERY PLAN " + sql, args):
                    print(" ", plan[3])
        print("Each adapter executes one duration SELECT; canonical reader: catalog + aggregate + duration = 3 SELECTs.")
        print("No Android/JVM elapsed time or heap measurement is produced by this host probe.")


if __name__ == "__main__":
    main()
