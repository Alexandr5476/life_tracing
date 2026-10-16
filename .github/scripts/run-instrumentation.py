"""Run the two full GMD suites sequentially and retain original JUnit evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def collect_reports(repo, directory, module, api):
    reports, counts, observed = [], dict(total=0, failures=0, errors=0, skipped=0), set()
    source = repo / module / "build/outputs/androidTest-results/managedDevice/debug" / f"pixel2Api{api}"
    for path in sorted(source.glob("TEST-*.xml")):
        tree = ET.parse(path)
        cases = list(tree.iter("testcase"))
        before = counts.copy()
        counts["total"] += len(cases)
        for case in cases:
            for tag, key in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                counts[key] += case.find(tag) is not None
            if (case.get("classname", "").endswith(".AndroidCompatibilityDeviceTest")
                    and case.get("name", "").startswith("recordsActualApiAndRejectsMismatchedRequestedTarget[")
                    and not any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))):
                observed.update(int(value) for value in re.findall(r"actualApi=(\d+)", case.get("name", "")))
        for attribute, key in (("tests", "total"), ("failures", "failures"), ("errors", "errors"), ("skipped", "skipped")):
            declared = tree.getroot().get(attribute)
            if declared is not None and int(declared) != counts[key] - before[key]:
                raise ValueError(f"{module}: inconsistent JUnit {attribute} count")
        destination = directory / path.relative_to(repo)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        reports.append({"path": destination.relative_to(directory).as_posix(),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return dict(counts, observed_apis=sorted(observed), reports=reports)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True, type=int, choices=(26, 31, 33, 35, 36))
    args = parser.parse_args()
    repo = Path.cwd()
    directory = repo / "ci-evidence" / f"api{args.api}"
    directory.mkdir(parents=True, exist_ok=True)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    expected = os.environ["EXPECTED_TEST_SHA"]
    if head != expected:
        raise RuntimeError(f"Checkout mismatch: tested {head}, expected {expected}")
    evidence = dict(tested_sha=head, event_sha=os.environ["GITHUB_SHA"],
                    api=args.api, run_id=int(os.environ["GITHUB_RUN_ID"]),
                    run_attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                    event=os.environ["GITHUB_EVENT_NAME"], started_at=timestamp(), modules={})
    failed = False
    try:
        if args.api == 26:
            # API 26's image omits the emulator dependency; AGP 8.10.1 queries it at setup line 194.
            subprocess.run(["sdkmanager", "--install", "emulator"], check=True)
        for module in ("data", "app"):
            command = ["./gradlew", f":{module}:pixel2Api{args.api}DebugAndroidTest",
                       f"-Plifetracing.compatibility.expectedApi={args.api}",
                       "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect",
                       "-Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5",
                       "--no-parallel", "--max-workers=2", "--no-daemon", "--no-configuration-cache",
                       "--rerun-tasks", "--no-build-cache", "--console=plain", "--stacktrace"]
            record = dict(command=command, started_at=timestamp(), exit_code=None)
            evidence["modules"][module] = record
            print("command: " + " ".join(command), flush=True)
            # Separate invocations prevent shared AVD setup/teardown races, even on failure.
            try:
                with (directory / f"{module}.log").open("w", encoding="utf-8") as log:
                    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                          text=True, encoding="utf-8", errors="replace") as process:
                        for line in process.stdout:
                            print(line, end="", flush=True)
                            log.write(line)
                        record["exit_code"] = process.wait()
                record.update(collect_reports(repo, directory, module, args.api))
            except (OSError, ET.ParseError, ValueError) as exc:
                record["evidence_error"] = str(exc)
                print(f"::error::{module} API {args.api}: {exc}", flush=True)
            record["finished_at"] = timestamp()
            passed = (record["exit_code"] == 0 and record.get("total", 0) > 0
                      and record.get("observed_apis") == [args.api]
                      and not any(record.get(key, 0) for key in ("failures", "errors")))
            failed |= not passed
            print(f"module={module} api={args.api} exit_code={record['exit_code']} "
                  f"tests={record.get('total', 0)} PASS={passed}", flush=True)
    finally:
        evidence["finished_at"] = timestamp()
        (directory / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
