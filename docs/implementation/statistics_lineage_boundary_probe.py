"""PR37 W1: persisted physical v10 SQL, indexed plans, exact arithmetic and native VM work.

python docs/implementation/statistics_lineage_boundary_probe.py
--repository-ref b555e9e583036a245fa1438f900391aabef51758 fails the boundary assertion.
Host measurements are deterministic SQLite instruction counts, not Android latency gates.
"""
import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from statistics_duration_probe import ROOT, query, reference
from statistics_number_probe import insert
from statistics_review_probe import schema, plan, distribution
from statistics_shared_lineage_probe import measured
from statistics_small_fallback_probe import small_fixture, broad_value, grouped
from statistics_signed_rank_probe_support import histograms


def strategy(db, kind, series, rows, extended=True, work=True, bounded=None, post_trial=None):
    """Mirror the two production fair stages; all SQL is extracted from production sources."""
    args = dict(seriesId=series, startDate=None, endDate=None)
    q = db.execute(query(kind + "NumberExecutionCandidateCount"), args).fetchone()[0]
    result = {}
    visits = native = probe_count = indexed = indexed_candidates = 0
    for multiplier in ([2, 15] if extended else [2]):
        if multiplier != 2 and len(rows) - len(result) <= 3:
            break
        if multiplier != 2 and len(rows) - len(result) <= 16:
            multiplier = 10
        cap = min(2 * q + 1, multiplier * q // len(rows) + 1)
        for row in rows:
            field = row["source_field_id"]
            if field in result:
                continue
            probe = query(kind + ("NumberFieldCandidateCount" if multiplier == 2 else "NumberFieldCandidateLimitReached"))
            bindings = dict(fieldId=field, limit=cap)
            paths = plan(db, probe, bindings)
            assert any("source_field_id" in path for path in paths), paths
            assert any("execution_values_snapshot_field_id" in path for path in paths), paths
            value = db.execute(probe, bindings).fetchone()[0]
            # OFFSET returns existence, so use its visit upper bound rather than counting rows again.
            count = value if multiplier == 2 else cap if value else cap - 1
            assert count <= cap
            visits += count if multiplier == 2 else cap
            probe_count += 1
            native += measured(db, probe, bindings) if work else 0
            if count < cap:
                select = query(kind + "DenseSeriesNumberSelection")
                bindings = dict(args, fieldId=field, expectedCount=row["sample_count"])
                paths = plan(db, select, bindings)
                assert "fields" in next(p for p in paths if "SEARCH " in p.upper()), paths
                assert any("execution_values_snapshot_field_id" in p for p in paths), paths
                result[field] = db.execute(select, bindings).fetchone()
                native += measured(db, select, bindings) if work else 0
                indexed += 1
                indexed_candidates += count
    remaining = [row for row in rows if row["source_field_id"] not in result]
    passes = scalars = 0
    if len(remaining) > 3:
        source = (ROOT / "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt").read_text(encoding="utf-8")
        if bounded is None:
            bounded = extended and "GroupedNumberHistograms" in source
        if post_trial is None:
            post_trial = "if (unresolved.size > SMALL_FALLBACK_FIELD_LIMIT)" in source
        selected, rank_work, passes = grouped(db, kind, series, remaining, work,
                                            3 if len(remaining) <= 16 or bounded else 65)
        result.update(selected)
        native += rank_work
        unresolved = [r for r in remaining if r["source_field_id"] not in result]
        if bounded and len(unresolved if post_trial else remaining) > 16:
            selected, histogram_work, _ = histograms(
                db, kind, series, unresolved, work)
            result.update(selected)
            native += histogram_work
    for row in remaining:
        field = row["source_field_id"]
        if field in result:
            continue
        sql = query(kind + "SeriesNumberSelection")
        bindings = dict(args, fieldId=field, expectedCount=row["sample_count"])
        paths = plan(db, sql, bindings)
        assert "executions" in next(p for p in paths if "SEARCH " in p.upper()), paths
        result[field] = db.execute(sql, bindings).fetchone()
        native += measured(db, sql, bindings) if work else 0
        scalars += 1
    assert visits <= (17 if extended else 2) * q + (2 if extended else 1) * len(rows)
    assert indexed_candidates <= (15 if extended else 2) * q
    assert scalars <= 16
    return result, native, (probe_count, indexed, passes, scalars)


def run_case(kind, fields, old, selected, extended, sparse=False):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "boundary.db"
        with closing(sqlite3.connect(path)) as db:
            schema(db)
            series, expected = small_fixture(db, kind, fields, False, selected, old_rows=old)
            if not sparse:
                for f in range(1, fields, 2):
                    value = broad_value(selected - 1, f)
                    insert(db, kind + "_execution_field_values", {
                        kind + "_execution_id": f"{series}-{selected - 1}",
                        "snapshot_field_id": f"{series}-{kind}-field-{f}", "number_scaled": value})
                    expected[f"{kind}-source-{f}"].append(value)
            db.commit()
        with closing(sqlite3.connect(path)) as db:
            db.row_factory = sqlite3.Row
            args = dict(seriesId=series, startDate=None, endDate=None)
            rows = db.execute(query(kind + "NumberSummaries"), args).fetchall()
            for row in rows:
                field = row["source_field_id"]
                assert db.execute(f"SELECT COUNT(DISTINCT id) FROM {kind}_snapshot_fields WHERE source_field_id=?",
                                  (field,)).fetchone()[0] == 2
            before = db.total_changes
            statements = []
            db.set_trace_callback(statements.append)
            result, native, shape = strategy(db, kind, series, rows, extended)
            db.set_trace_callback(None)
            assert any("LIMIT" in sql and "LEFT JOIN" in sql for sql in statements)
            assert sum(" AS lower_median_scaled" in sql for sql in statements) == 2 * (shape[1] + shape[3])
            assert sum(" AS at_or_below_count" in sql for sql in statements) == 2 * shape[2]
            assert db.total_changes == before
            _, previous, old_shape = strategy(db, kind, series, rows, False)
            for row in rows:
                field = row["source_field_id"]
                exact = reference(expected[field].copy())
                assert distribution([row], result[field]) == exact
                bindings = dict(args, fieldId=field, expectedCount=row["sample_count"])
                bits = db.execute(query(kind + "NumberBitCounts"), bindings).fetchall()
                for suffix in ("DenseSeriesNumberSelection", "SeriesNumberSelection"):
                    individual = db.execute(query(kind + suffix), bindings).fetchone()
                    assert distribution(bits, individual) == exact
                assert exact[0] == selected - (int(field.rsplit("-", 1)[1]) % 2 if sparse else 0)
                assert abs(exact[1]) > 2**63 - 1
                assert exact[-2:] == (-(2**63), 2**63 - 1)
            # Source presentation changes cannot change source identity or arithmetic.
            db.execute(f"UPDATE {kind}_template_fields SET name='Renamed ' || (1000-position)")
            renamed = db.execute(query(kind + "NumberSummaries"), args).fetchall()
            assert [tuple(r) for r in rows] == [tuple(r) for r in renamed]
            print(kind, "P", fields, "selected/old", selected, old, "sparse", sparse,
                  "reviewed/corrected VM", previous, native, "shapes", old_shape, shape, flush=True)
            if old <= 1002:
                assert shape[1] == fields and shape[2:] == (0, 0), "W1: moderate lineages fell back to repeated rank traversals"
            return native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-ref")
    parser.add_argument("--kind", choices=("activity", "sequence"))
    parser.add_argument("--fields", nargs="+", type=int, default=[16, 17, 24])
    parser.add_argument("--old", nargs="+", type=int)
    parser.add_argument("--selected", type=int, default=1000)
    parser.add_argument("--sparse", action="store_true")
    args = parser.parse_args()
    path = "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt"
    source = (subprocess.check_output(["git", "show", args.repository_ref + ":" + path], cwd=ROOT).decode()
              if args.repository_ref else (ROOT / path).read_text(encoding="utf-8"))
    extended = "INDEXED_NUMBER_SORT_WORK" in source
    print("SQLite", sqlite3.sqlite_version, "extended admission", extended, flush=True)
    for kind in ([args.kind] if args.kind else ["activity", "sequence"]):
        for fields in args.fields:
            work = {}
            crossover = 9000 if fields <= 16 else 14000
            old_counts = args.old or [999, 1000, 1001, 1002, crossover-1, crossover, crossover+1, crossover+2]
            for old in old_counts:
                work[old] = run_case(kind, fields, old, args.selected, extended, args.sparse)
            if 1000 in work and 1001 in work:
                assert work[1001] <= work[1000] * 1.5, "W1: SQL-work cliff at the old fair allowance"
            if crossover in work and crossover+1 in work:
                # Unlike the old 9.5x cliff, the fallback at the cost crossover has comparable work.
                assert work[crossover+1] <= work[crossover] * 2.5, "Sort/rank crossover merely moved the severe work cliff"
    print("PASS persisted/reopened exact results, stable identities, indexed plans, bounded candidates and VM work")


if __name__ == "__main__":
    main()
