"""Exact histogram driver for the W1 host probe; expands the production Kotlin SQL template."""

from collections import defaultdict
import re

from statistics_duration_probe import ROOT
from statistics_review_probe import plan
from statistics_shared_lineage_probe import measured


def histogram_query(kind, ranges, shift):
    source = (ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/GroupedNumberHistograms.kt").read_text(encoding="utf-8")
    template = source[source.index("    val sql ="):source.index("    return SimpleSQLiteQuery")]
    sql = "".join(re.findall(r'"([^"\n]*)"', template))
    values = {field.encode("utf-8").hex(): bounds for field, bounds in ranges.items()}
    keys = sorted(values)

    def range_expression(start, end, lower):
        if end - start == 1:
            return str(values[keys[start]][0 if lower else 1])
        middle = (start + end) // 2
        return (f"CASE WHEN fields.source_field_id COLLATE BINARY < CAST(X'{keys[middle]}' AS TEXT) "
                f"THEN {range_expression(start, middle, lower)} ELSE {range_expression(middle, end, lower)} END")

    thresholds = [-(2**63)] + [-(1 << bit) for bit in range(62, -1, -1)] + [0] + [1 << bit for bit in range(63)]

    def magnitude(start, end):
        if end - start == 1:
            return str(start)
        middle = (start + end) // 2
        return (f"CASE WHEN recorded_values.number_scaled < {thresholds[middle]} "
                f"THEN {magnitude(start, middle)} ELSE {magnitude(middle, end)} END")

    bucket = magnitude(0, len(thresholds)) if shift is None else f"((recorded_values.number_scaled >> {shift}) & 255)"
    predicate = "" if shift is None else (
        f"AND recorded_values.number_scaled BETWEEN ({range_expression(0, len(keys), True)}) "
        f"AND ({range_expression(0, len(keys), False)}) ")
    eligible = ("executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL" if kind == "activity" else
                "executions.status IN ('COMPLETED', 'ENDED_EARLY')")
    ids = ",".join(f"CAST(X'{key}' AS TEXT)" for key in keys)
    # rangePredicate is a Kotlin expression between quoted SQL fragments.
    sql = sql.replace("GROUP BY fields", predicate + "GROUP BY fields")
    return sql.replace("${table}", kind).replace("$eligible", eligible).replace("$ids", ids).replace("$bucket", bucket)


def histograms(db, kind, series, summaries, measure_work=True):
    searches = {r["source_field_id"]: dict(bounds=(r["minimum_scaled"], r["maximum_scaled"]),
                 count=r["sample_count"], remaining=r["sample_count"],
                 ranks=[(r["sample_count"] - 1) // 2, r["sample_count"] // 2], endpoints=[None, None]) for r in summaries}
    result = {}
    native = passes = 0
    while searches:
        assert passes < 9
        ranges = {field: search["bounds"] for field, search in searches.items()}
        shift = None if passes == 0 else max(((a ^ b).bit_length() - 1) // 8 * 8 for a, b in ranges.values())
        sql = histogram_query(kind, ranges, shift)
        args = (series, None, None, None)
        paths = plan(db, sql, args)
        searches_plan = [p for p in paths if "SEARCH " in p.upper()]
        assert len(searches_plan) == 3 and "executions" in searches_plan[0], paths
        assert "statistics_series_id" in searches_plan[0], paths
        assert any("recorded_values" in p and "execution_id" in p for p in searches_plan), paths
        assert not any("CORRELATED" in p.upper() for p in paths), paths
        native += measured(db, sql, args) if measure_work else 0
        buckets = defaultdict(list)
        for row in db.execute(sql, args):
            buckets[row["source_field_id"]].append(row)
        assert set(buckets) == set(searches)
        for field, rows in buckets.items():
            search = searches[field]
            assert len(rows) <= 256
            assert sum(r["sample_count"] for r in rows) == search["remaining"]
            preceding = 0
            next_bounds = None
            for row in rows:
                count, low, high = row["sample_count"], row["minimum_scaled"], row["maximum_scaled"]
                for index, rank in enumerate(search["ranks"]):
                    if search["endpoints"][index] is not None or not preceding <= rank < preceding + count:
                        continue
                    local = rank - preceding
                    if local == 0 or low == high:
                        search["endpoints"][index] = low
                    elif local == count - 1:
                        search["endpoints"][index] = high
                    else:
                        assert next_bounds is None or next_bounds == (low, high)
                        next_bounds = (low, high)
                        search["remaining"] = count
                        search["ranks"][index] = local
                preceding += count
            if all(v is not None for v in search["endpoints"]):
                result[field] = dict(sample_count=search["count"], selected_count=2-search["count"] % 2,
                                     lower_median_scaled=search["endpoints"][0], upper_median_scaled=search["endpoints"][1])
                del searches[field]
            else:
                search["bounds"] = next_bounds
        passes += 1
    return result, native, passes
