"""W1 signed-Long second-stage boundary: production SQL, physical reopen, native VM work.

Run: python docs/implementation/statistics_signed_rank_probe.py
Host evidence only. Android Room execution remains Controller-owned.
"""

from contextlib import closing
from pathlib import Path
import argparse
import sqlite3
import tempfile

from statistics_duration_probe import ROOT, query, reference
from statistics_review_probe import schema, plan, distribution
from statistics_small_fallback_probe import small_fixture, broad_value
from statistics_lineage_boundary_probe import strategy


def signed_value(index):
    if index < 99:
        return -(2**63)
    if index == 99:
        return 0
    if index <= 162:
        return 1 << (index - 100)
    return 2**63 - 1 if index == 163 else 1


def clustered_value(index, field):
    if index < 2:
        return [-(2**63), 2**63 - 1][index]
    return (1 << 62) + (broad_value(index, field) & ((1 << 55) - 1))


def byte_worst_values():
    base = 1 << 62
    return [base] * 99 + [base + 1] + [base + (1 << i) for i in range(1, 62)] + [2**63 - 1] + [base + 2] * 38


def run(kind, fields, selected, old, sparse, reviewed=False, broad=False, worst=False):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "signed.db"
        with closing(sqlite3.connect(path)) as db:
            schema(db)
            series, _ = small_fixture(db, kind, fields, False, selected, old_rows=old)
            expected = {}
            for field in range(fields):
                count = selected - (field % 2 if sparse else 0)
                values = (byte_worst_values() if worst else
                          [clustered_value(i, field) if broad else signed_value(i) for i in range(count)])
                expected[f"{kind}-source-{field}"] = values
                for index in range(count):
                    db.execute(f"INSERT OR REPLACE INTO {kind}_execution_field_values "
                               f"({kind}_execution_id,snapshot_field_id,number_scaled) VALUES (?,?,?)",
                               (f"{series}-{index}", f"{series}-{kind}-field-{field}", values[index]))
            db.commit()
        with closing(sqlite3.connect(path)) as db:
            db.row_factory = sqlite3.Row
            rows = db.execute(query(kind + "NumberSummaries"),
                              dict(seriesId=series, startDate=None, endDate=None)).fetchall()
            statements = []
            db.set_trace_callback(statements.append)
            result, native, shape = strategy(db, kind, series, rows, bounded=False if reviewed else None)
            db.set_trace_callback(None)
            histogram_passes = sum(" AS bucket_id" in sql and not sql.startswith("EXPLAIN") for sql in statements) // 2
            for row in rows:
                field = row["source_field_id"]
                assert distribution([row], result[field]) == reference(expected[field].copy())
            print(kind, "P/E/old/sparse", fields, selected, old, sparse,
                  "broad/worst", broad, worst, "VM", native, "shape", shape, "histograms", histogram_passes, flush=True)
            assert shape[2] <= 3, "W1: signed distribution triggers dozens of full rank traversals"
            assert histogram_passes <= 9
            if broad and fields > 16:
                assert histogram_passes >= 2, "Broad fixture must exercise byte refinement SQL"
            if worst and fields > 16 and shape[1] == 0:
                assert histogram_passes == 9, "Witness must exercise all eight byte refinements"
            return native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=["activity", "sequence"])
    parser.add_argument("--fields", type=int, nargs="+", default=[16, 17, 24])
    parser.add_argument("--boundaries-only", action="store_true")
    parser.add_argument("--reviewed", action="store_true", help="Run the exact-head reviewed policy; fail-first control")
    parser.add_argument("--byte-worst-only", action="store_true")
    args = parser.parse_args()
    print("SQLite", sqlite3.sqlite_version, flush=True)
    for kind in ([args.kind] if args.kind else ["activity", "sequence"]):
        for fields in args.fields:
            boundary = 200 * (9 if fields <= 16 else 14)
            before = run(kind, fields, 200, boundary, False, args.reviewed, worst=args.byte_worst_only)
            after = run(kind, fields, 200, boundary + 1, False, args.reviewed, worst=args.byte_worst_only)
            print("Adjacent work ratio", after / before, flush=True)
            # Fixture-specific deterministic work guard; the algorithmic bound is traversal count.
            assert after <= before * 2.5, "W1: large native-work discontinuity at the second allowance"
            if not args.boundaries_only and not args.byte_worst_only:
                for selected, sparse in [(199, False), (201, False), (200, True)]:
                    run(kind, fields, selected, boundary + 1, sparse)
                run(kind, fields, 202, 3001, True, broad=True)
    print("PASS physical reopen, exact signed arithmetic, indexed SQL plans and fixed traversal/row bounds")


if __name__ == "__main__":
    main()
