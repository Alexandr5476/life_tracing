"""B2 fail-first and corrected median work on actual v10 SQL; no latency gate."""

import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from statistics_duration_probe import DAO, ROOT, query, reference
from statistics_number_probe import insert
from statistics_number_s3_probe import fixture
from statistics_review_probe import schema, extract, plan, measure, distribution


def reader(ref):
    if ref is None:
        return query
    source = subprocess.check_output(
        ["git", "show", ref + ":" + DAO.relative_to(ROOT).as_posix()], cwd=ROOT).decode("utf-8")
    return lambda name: extract(source, name)


def strategy(db, kind, bindings, candidates, read):
    try:
        sql = read(kind + "NumberFieldCandidateCount")
    except ValueError:  # Reviewed B2 baseline policy and individual unconditional path.
        history = db.execute(read(kind + "HistoryExecutionCount")).fetchone()[0]
        if bindings["expectedCount"] * 2 < history:
            return kind + "SeriesNumberSelection", None, None
        sql = read(kind + "NumberFieldLineageCount")
        budget = bindings["expectedCount"]
    else:
        budget = 2 * candidates
    params = dict(fieldId=bindings["fieldId"], limit=budget + 1)
    count = db.execute(sql, params).fetchone()[0]
    return kind + ("DenseSeriesNumberSelection" if count <= budget else "SeriesNumberSelection"), sql, params


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=800)
    parser.add_argument("--fields", type=int, default=24)
    parser.add_argument("--kind", choices=("activity", "sequence", "both"), default="both")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--query-ref")
    parser.add_argument("--sparse-first", action="store_true", help="Exercise the sparse regression before dense Fields")
    parser.add_argument("--baseline-ref", default="b074d9a2477e42e73c6c82860ae5b7b8dfee1129")
    args = parser.parse_args()
    assert args.rows >= 10 and args.fields >= 2
    inspected, baseline = reader(args.query_ref), reader(args.baseline_ref)
    kinds = ("activity", "sequence") if args.kind == "both" else (args.kind,)
    print("SQLite", sqlite3.sqlite_version, "selected", args.rows, "unrelated", 3 * args.rows, "Fields", args.fields)
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "b2.db"
        with closing(sqlite3.connect(path)) as db:
            schema(db)
            for kind in kinds:
                series, _ = fixture(db, kind, args.rows, args.fields)
                # Exclusive sparse lineage, amidst many other populated Fields.
                db.execute(f"DELETE FROM {kind}_execution_field_values WHERE snapshot_field_id=?",
                           (f"{kind}-field-{args.fields - 1}",))
                insert(db, kind + "_execution_field_values", {
                    kind + "_execution_id": kind + "-0", "snapshot_field_id": f"{kind}-field-{args.fields - 1}",
                    "number_scaled": -(2**63)})
                insert(db, "statistics_series", dict(id=kind + "-unrelated", kind=kind.upper(),
                       display_name="Unrelated", created_at_ms=0))
                # Unrelated executions share no source-Field values with the target.
                original = dict(db.execute(f"SELECT * FROM {kind}_executions LIMIT 1").fetchone())
                for index in range(3 * args.rows):
                    insert(db, kind + "_executions", dict(original, id=f"{kind}-unrelated-{index}",
                           statistics_series_id=kind + "-unrelated"))
                db.execute(f"UPDATE {kind}_templates SET deleted_at_ms=2")
            db.commit()
        with closing(sqlite3.connect(path)) as db:
            db.row_factory = sqlite3.Row
            for kind in kinds:
                for scenario in ("minority", "shared-tiny", "narrow", "unused-metadata"):
                    if scenario == "shared-tiny":
                        insert(db, "statistics_series", dict(id=kind + "-tiny", kind=kind.upper(),
                               display_name="New Series", created_at_ms=0))
                        db.execute(f"UPDATE {kind}_executions SET statistics_series_id=? WHERE id IN (?, ?, ?)",
                                   (kind + "-tiny", kind + "-0", kind + "-1", kind + "-2"))
                        db.execute(f"UPDATE {kind}_executions SET primary_local_date='2026-08-21' WHERE id=?",
                                   (kind + "-3",))
                    if scenario == "unused-metadata":
                        for index in range(2 * args.rows * args.fields + 5):
                            snapshot = f"{kind}-unused-{index}"
                            facts = dict(id=snapshot, name="Unused", created_at_ms=0)
                            if kind == "activity":
                                facts["time_tracking_mode"] = "NO_LIVE_TRACKING"
                            insert(db, kind + "_snapshots", facts)
                            insert(db, kind + "_snapshot_fields", dict(
                                id=snapshot + "-field", source_field_id=kind + "-source-0", position=0,
                                name_at_creation="Unused", field_type="NUMBER",
                                **{("snapshot_id" if kind == "activity" else "sequence_snapshot_id"): snapshot}))
                    series = kind + ("-tiny" if scenario == "shared-tiny" else "-series")
                    date = "2026-08-21" if scenario == "narrow" else None
                    bindings = dict(seriesId=series, startDate=date, endDate=date)
                    relevant = db.execute(query(kind + "NumberExecutionCount"), bindings).fetchone()[0]
                    # Test-owned independent raw values; production returns only scalar projections.
                    expected = {}
                    for row in db.execute(
                        f"SELECT fields.source_field_id, values_.number_scaled FROM {kind}_execution_field_values values_ "
                        f"JOIN {kind}_executions e ON e.id=values_.{kind}_execution_id "
                        f"JOIN {kind}_snapshot_fields fields ON fields.id=values_.snapshot_field_id "
                        "WHERE e.statistics_series_id=? AND (? IS NULL OR e.primary_local_date=?) "
                        "AND values_.number_scaled IS NOT NULL", (series, date, date)):
                        expected.setdefault(row[0], []).append(row[1])
                    summaries = db.execute(inspected(kind + "NumberSummaries"), bindings).fetchall()
                    if args.sparse_first:
                        summaries.sort(key=lambda row: row["source_field_id"] != f"{kind}-source-{args.fields - 1}")
                    assert len(summaries) == len(expected)
                    assert all(len(values) <= relevant for values in expected.values())
                    if scenario == "minority":
                        assert relevant == args.rows
                        assert len(expected[f"{kind}-source-{args.fields - 1}"]) == 1
                    candidate_sql = query(kind + "NumberExecutionCandidateCount")
                    costs, cost_ms, cost_steps = measure(db, candidate_sql, bindings, args.repeats)
                    execution_candidates = costs[0][0]
                    cost_plan = plan(db, candidate_sql, bindings)
                    assert "executions" in next(line for line in cost_plan if "SEARCH " in line)
                    assert any("COVERING INDEX" in line and "execution_field_values" in line for line in cost_plan)
                    assert execution_candidates >= relevant
                    print(kind, scenario, "execution/value cost", execution_candidates, "VM~", cost_steps, "ms", cost_ms)
                    work = {"baseline": 0, "corrected": cost_steps[0]}
                    for summary in summaries:
                        field = summary["source_field_id"]
                        params = dict(bindings, fieldId=field, expectedCount=summary["sample_count"])
                        method, preflight, cap = strategy(db, kind, params, execution_candidates, inspected)
                        sparse = field == f"{kind}-source-{args.fields - 1}"
                        field_first = scenario == "minority" or sparse or (
                            scenario == "unused-metadata" and field != kind + "-source-0")
                        # Small smoke fixtures may make even an old lineage cheaper than selected value work.
                        if scenario in ("shared-tiny", "narrow") and args.rows <= 4 * args.fields:
                            field_first = db.execute(query(kind + "NumberFieldCandidateCount"),
                                dict(fieldId=field, limit=2 * execution_candidates + 1)).fetchone()[0] <= 2 * execution_candidates
                        assert ("Dense" in method) == field_first, ("B2 path", kind, scenario, field, method)
                        if preflight:
                            details = plan(db, preflight, cap)
                            assert "LIMIT :limit" in preflight and "LEFT JOIN" in preflight
                            assert any("source_field_id" in line for line in details), details
                            assert any("execution_values_snapshot_field_id" in line for line in details), details
                            assert db.execute(preflight, cap).fetchone()[0] <= cap["limit"]
                        corrected, ms, steps = measure(db, inspected(method), params, args.repeats)
                        assert distribution([summary], corrected[0]) == reference(expected[field].copy())
                        # Individual read must share this policy in production.
                        individual = db.execute(inspected(kind + "NumberBitCounts"), params).fetchall()
                        assert distribution(individual, corrected[0]) == reference(expected[field].copy())
                        pre_steps = measure(db, preflight, cap, args.repeats)[2][0] if preflight else 0
                        old_method, _, _ = strategy(db, kind, params, execution_candidates, baseline)
                        old, old_ms, old_steps = measure(db, baseline(old_method), params, args.repeats)
                        assert distribution([summary], old[0]) == reference(expected[field].copy())
                        work["baseline"] += old_steps[0]
                        work["corrected"] += steps[0] + pre_steps
                        if field in (kind + "-source-0", f"{kind}-source-{args.fields - 1}"):
                            print(kind, scenario, field, method, "baseline VM~", old_steps, "ms", old_ms,
                                  "corrected median VM~", steps, "preflight VM~", pre_steps, "ms", ms)
                            print("  plan", plan(db, inspected(method), params))
                        if scenario == "minority":
                            assert steps[0] + pre_steps + cost_steps[0] < old_steps[0], (field, steps, pre_steps, cost_steps, old_steps)
                    print(kind, scenario, "complete detail median+preflight work", work,
                          "fixed result rows", len(summaries), "queries", 2 + 2 * len(summaries))
            assert not db.execute("PRAGMA foreign_key_check").fetchall()
    print("PASS exact reference, archived sources, physical reopen, both kinds and selective W1 paths")


if __name__ == "__main__":
    main()
