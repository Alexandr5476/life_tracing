"""W1 mixed post-trial dispatch: physical v10 SQLite, production SQL and VM work.

Run: python docs/implementation/statistics_mixed_trial_probe.py
--reviewed forces f36c4e4's original-count dispatch and fails the structural guard.
Host evidence only; physical Room execution remains Controller-owned.
"""

import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile

from statistics_duration_probe import query, reference
from statistics_lineage_boundary_probe import strategy
from statistics_review_probe import schema, plan, distribution
from statistics_shared_lineage_probe import measured
from statistics_signed_rank_probe_support import histograms, histogram_query
from statistics_small_fallback_probe import small_fixture, grouped


def value(index, field, hard):
    if field >= hard:
        return 7
    base = 1 << 62
    positive = (base if index < 99 else base + 1 if index == 99 else
                base + (1 << (index - 99)) if index <= 160 else
                2**63 - 1 if index == 161 else base + 2)
    return positive if field % 2 == 0 else -positive


def run(kind, fields, hard, reviewed):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "mixed.db"
        with closing(sqlite3.connect(path)) as db:
            schema(db)
            series, _ = small_fixture(db, kind, fields, False, 200, old_rows=3001)
            expected = {}
            for field in range(fields):
                samples = [value(i, field, hard) for i in range(200 - field % 2)]
                expected[f"{kind}-source-{field}"] = samples
                db.executemany(f"UPDATE {kind}_execution_field_values SET number_scaled=? "
                               f"WHERE {kind}_execution_id=? AND snapshot_field_id=?",
                               [(v, f"{series}-{i}", f"{series}-{kind}-field-{field}")
                                for i, v in enumerate(samples)])
            db.commit()
            assert not db.execute("PRAGMA foreign_key_check").fetchall()
        with closing(sqlite3.connect(path)) as db:
            db.row_factory = sqlite3.Row
            args = dict(seriesId=series, startDate=None, endDate=None)
            rows = db.execute(query(kind + "NumberSummaries"), args).fetchall()
            changes = db.total_changes
            resolved, _, passes = grouped(db, kind, series, rows, False, 3)
            unresolved = [r for r in rows if r["source_field_id"] not in resolved]
            assert passes == 3 and len(unresolved) == hard
            if fields == 17 and hard == 1:
                ranges = {r["source_field_id"]: (r["minimum_scaled"], r["maximum_scaled"]) for r in unresolved}
                print(kind, "histogram EXPLAIN",
                      plan(db, histogram_query(kind, ranges, None), (series, None, None, None)), flush=True)
            _, histogram_vm, histogram_passes = histograms(db, kind, series, unresolved)
            assert histogram_passes == 9, "Positive cluster must force all byte refinements"
            scalar_vm = 0
            for row in unresolved:
                sql = query(kind + "SeriesNumberSelection")
                bindings = dict(args, fieldId=row["source_field_id"], expectedCount=row["sample_count"])
                paths = plan(db, sql, bindings)
                assert "executions" in next(p for p in paths if "SEARCH " in p.upper()), paths
                assert any("statistics_series_id" in p for p in paths), paths
                assert any("recorded_values" in p and "execution_id" in p for p in paths), paths
                assert any("SEARCH fields" in p and "(id=?)" in p for p in paths), paths
                scalar_vm += measured(db, sql, bindings)
                if fields == 17 and hard == 1:
                    print(kind, "scalar EXPLAIN", paths, flush=True)
            assert histogram_vm > scalar_vm
            traces = []
            db.set_trace_callback(traces.append)
            result, _, shape = strategy(db, kind, series, rows, work=False,
                                        post_trial=False if reviewed else None)
            db.set_trace_callback(None)
            histogram_reads = sum(" AS bucket_id" in s and not s.startswith("EXPLAIN") for s in traces)
            for row in rows:
                field = row["source_field_id"]
                assert distribution([row], result[field]) == reference(expected[field].copy())
                individual_args = dict(args, fieldId=field, expectedCount=row["sample_count"])
                bits = db.execute(query(kind + "NumberBitCounts"), individual_args).fetchall()
                median = db.execute(query(kind + "SeriesNumberSelection"), individual_args).fetchone()
                assert distribution(bits, median) == reference(expected[field].copy())
            assert db.total_changes == changes
            print(kind, "P/hard", fields, hard, "shape", shape, "histogram reads", histogram_reads,
                  "completion VM histogram/scalar", histogram_vm, scalar_vm, flush=True)
            assert shape == (2 * fields, 0, 3, hard), "W1: completion must use the post-trial Field count"
            assert histogram_reads == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reviewed", action="store_true")
    parser.add_argument("--kind", choices=["activity", "sequence"])
    args = parser.parse_args()
    print("SQLite", sqlite3.sqlite_version, flush=True)
    for kind in ([args.kind] if args.kind else ["activity", "sequence"]):
        for fields in [17, 24]:
            for hard in [1, 2, 3]:
                run(kind, fields, hard, args.reviewed)
    print("PASS physical reopen, exact parity, nine-pass witness and bounded scalar completion")


if __name__ == "__main__":
    main()
