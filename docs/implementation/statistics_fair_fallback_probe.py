"""PR37 round-two F1/F2: exact SQL work and fair strategy evidence, not device timing.

python docs/implementation/statistics_fair_fallback_probe.py
Use --repository-ref f12f55d to demonstrate both reviewed strategy failures.
Room tests inspect executed Kotlin dispatch; this host probe mirrors only that policy.
"""
import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from statistics_duration_probe import ROOT, query, reference
from statistics_review_probe import schema, distribution, plan
from statistics_shared_lineage_probe import measured
from statistics_small_fallback_probe import small_fixture, grouped


STAGED = False


def strategy(db, kind, series, rows, corrected, work=True):
    if corrected and STAGED:
        from statistics_lineage_boundary_probe import strategy as staged_strategy
        return staged_strategy(db, kind, series, rows, extended=True, work=work)
    bindings = dict(seriesId=series, startDate=None, endDate=None)
    q = db.execute(query(kind + "NumberExecutionCandidateCount"), bindings).fetchone()[0]
    remaining, result = [], {}
    budget = 2 * q
    total = probes = field_first = scalars = 0
    visits = 0
    for row in rows:
        field = row["source_field_id"]
        limit = 2 * q // len(rows) + 1 if corrected else budget + 1
        accepted = False
        if corrected or budget:
            sql = query(kind + "NumberFieldCandidateCount")
            args = dict(fieldId=field, limit=limit)
            candidates = db.execute(sql, args).fetchone()[0]
            total += measured(db, sql, args) if work else 0
            probes += 1
            visits += candidates
            accepted = candidates < limit
            if not corrected:
                budget = budget - candidates if accepted else 0
        if accepted:
            args = dict(bindings, fieldId=field, expectedCount=row["sample_count"])
            sql = query(kind + "DenseSeriesNumberSelection")
            assert "fields" in next(p for p in plan(db, sql, args) if "SEARCH " in p.upper())
            total += measured(db, sql, args) if work else 0
            result[field] = db.execute(sql, args).fetchone()
            field_first += 1
        else:
            remaining.append(row)
    if len(remaining) > (3 if corrected else 2):
        # Three cheap shared passes preserve the duplicate-heavy earlier F1 fixture.
        passes_limit = 3 if corrected and len(remaining) <= 16 else 65
        selected, native_work, passes = grouped(db, kind, series, remaining, work, passes_limit)
        result.update(selected)
        total += native_work
    else:
        passes = 0
    for row in remaining:
        field = row["source_field_id"]
        if field in result:
            continue
        sql = query(kind + "SeriesNumberSelection")
        args = dict(bindings, fieldId=field, expectedCount=row["sample_count"])
        assert "executions" in next(p for p in plan(db, sql, args) if "SEARCH " in p.upper())
        total += measured(db, sql, args) if work else 0
        result[field] = db.execute(sql, args).fetchone()
        scalars += 1
    if corrected:
        assert visits <= 2 * q + len(rows)
        assert scalars <= 16
    return result, total, (probes, field_first, passes, scalars)


def check(db, kind, series, expected, corrected, fair):
    args = dict(seriesId=series, startDate=None, endDate=None)
    rows = db.execute(query(kind + "NumberSummaries"), args).fetchall()
    if fair:
        rows.sort(key=lambda r: db.execute(f"SELECT name FROM {kind}_template_fields WHERE id=?",
                                         (r["source_field_id"],)).fetchone()[0])
    chosen, work, shape = strategy(db, kind, series, rows, corrected)
    for row in rows:
        field = row["source_field_id"]
        exact = reference(expected[field].copy())
        assert distribution([row], chosen[field]) == exact
        individual = dict(args, fieldId=field, expectedCount=row["sample_count"])
        bits = db.execute(query(kind + "NumberBitCounts"), individual).fetchall()
        select = db.execute(query(kind + "NumberSelection"), individual).fetchone()
        assert distribution(bits, select) == exact
        assert exact[0] in (len(expected[next(iter(expected))]), len(expected[next(iter(expected))]) - 1)
    return rows, work, shape


def run(args, corrected):
    print("SQLite", sqlite3.sqlite_version, "corrected policy", corrected, flush=True)
    for kind in ((args.kind,) if args.kind else ("activity", "sequence")):
        for fields in ([24] if args.case == "fair" else args.fields):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "round-two.db"
                with closing(sqlite3.connect(path)) as db:
                    schema(db)
                    series, expected = small_fixture(db, kind, fields, False, args.rows,
                                                     old_rows=50_000 if args.case == "fair" else max(16_000, 2 * args.rows * fields + 1))
                    if args.case == "fair":
                        for f in range(1, fields):
                            db.execute(f"DELETE FROM {kind}_execution_field_values WHERE snapshot_field_id=?",
                                       (f"{kind}-field-{f}",))
                    db.commit()
                with closing(sqlite3.connect(path)) as db:
                    db.row_factory = sqlite3.Row
                    if args.case == "fair":
                        original = None
                        for label in ("A expensive", "Z expensive"):
                            db.execute(f"UPDATE {kind}_template_fields SET name=CASE WHEN id=? THEN ? ELSE 'M cheap' END",
                                       (kind + "-source-0", label))
                            rows, work, shape = check(db, kind, series, expected, corrected, True)
                            _, previous, old_shape = strategy(db, kind, series, rows, False)
                            print(kind, label, "reviewed/corrected VM", previous, work, "shapes", old_shape, shape, flush=True)
                            assert shape == (24, 23, 0, 1), "F2: an expensive lineage starved unrelated cheap Field probes"
                            assert work < previous if label.startswith("A") else work <= previous * 1.1
                            if original is not None:
                                assert abs(work - original) <= original // 1_000, "Presentation-only ordering materially changed native work"
                            original = work
                    else:
                        rows, work, shape = check(db, kind, series, expected, corrected, False)
                        _, previous, old_shape = strategy(db, kind, series, rows, False)
                        all_grouped, grouped_work, passes = grouped(db, kind, series, rows)
                        for row in rows:
                            assert distribution([row], all_grouped[row["source_field_id"]]) == reference(expected[row["source_field_id"]].copy())
                        scalar = sum(measured(db, query(kind + "SeriesNumberSelection"),
                                     dict(seriesId=series, startDate=None, endDate=None,
                                          fieldId=r["source_field_id"], expectedCount=r["sample_count"])) for r in rows)
                        print(kind, fields, "scalar/grouped VM", scalar, grouped_work, "passes", passes,
                              "reviewed/corrected incl probes", previous, work, "shape", shape, flush=True)
                        assert passes >= 8
                        if fields <= 16:
                            assert shape[2] == (0 if fields <= 3 else 3) and shape[3] > 0, "F1: small broad fallback repeats grouped traversals"
                            assert work < previous
                        else:
                            assert shape[2] == 3 and shape[3] == 0
                            assert work <= previous * 1.1
    print("PASS exact reopened SQL, individual parity, bounded probes/selection and indexed plans")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-ref")
    parser.add_argument("--rows", type=int, default=1_000)
    parser.add_argument("--kind", choices=("activity", "sequence"))
    parser.add_argument("--fields", type=int, nargs="+", default=[3, 4, 8, 16, 17, 24, 32, 40])
    parser.add_argument("--case", choices=("wide", "fair"), default="wide")
    args = parser.parse_args()
    source_path = "data/src/main/kotlin/com/alexandr5476/lifetracing/data/persistence/StatisticsRepository.kt"
    source = (subprocess.check_output(["git", "show", args.repository_ref + ":" + source_path], cwd=ROOT).decode()
              if args.repository_ref else (ROOT / source_path).read_text(encoding="utf-8"))
    corrected = "smallFallbackPassLimit" in source or "GroupedNumberHistograms" in source
    global STAGED
    STAGED = "INDEXED_NUMBER_SORT_WORK" in source
    run(args, corrected)


if __name__ == "__main__":
    main()
