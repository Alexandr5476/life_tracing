"""Run full GMD suites and independent startup sequentially; retain JUnit and native diagnostics."""

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


def sdkmanager_command():
    """Use the configured SDK even when its command-line tools are absent from PATH."""
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    manager = None
    if sdk_root:
        root = Path(sdk_root)
        candidates = [root / "cmdline-tools/latest/bin/sdkmanager"]
        candidates.extend(sorted((root / "cmdline-tools").glob("*/bin/sdkmanager")))
        candidates.append(root / "tools/bin/sdkmanager")
        manager = next((str(path) for path in candidates if path.is_file()), None)
    if manager is None:
        manager = shutil.which("sdkmanager")
    if manager is None:
        raise FileNotFoundError(
            "Cannot locate sdkmanager: set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK with "
            "Android command-line tools, or add sdkmanager to PATH."
        )
    return [manager] + ([f"--sdk_root={sdk_root}"] if sdk_root else []) + ["--install", "emulator"]


def retain_artifact(repo, directory, module, path):
    # Startup reuses app's AGP output path; retain each invocation independently.
    destination = directory / ("startup" if module == "startup" else "") / path.relative_to(repo)
    destination.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    with path.open("rb") as original, destination.open("wb") as retained:
        while chunk := original.read(1024 * 1024):
            retained.write(chunk)
            digest.update(chunk)
    return dict(path=destination.relative_to(directory).as_posix(), sha256=digest.hexdigest())


def capture_system_ui_diagnostics(directory, module, api):
    """Read the blocked emulator once; never dismiss a system dialog or retry a test."""
    target = directory / f"{module}-system-ui"
    target.mkdir(parents=True, exist_ok=True)
    result = dict(expected_api=api, started_at=timestamp(), commands=[])

    def text_output(value):
        return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""

    def capture(command):
        record = dict(command=command, started_at=timestamp(), return_code=None)
        stdout, stderr = "", ""
        try:
            completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       text=True, encoding="utf-8", errors="replace", timeout=5, check=False)
            stdout, stderr = text_output(completed.stdout), text_output(completed.stderr)
            record["return_code"] = completed.returncode
        except subprocess.TimeoutExpired as exc:
            stdout, stderr = text_output(exc.output), text_output(exc.stderr)
            record["error"] = str(exc)
        except OSError as exc:
            record["error"] = str(exc)
        record["finished_at"] = timestamp()
        content = ("command: " + json.dumps(command) + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr).encode("utf-8")
        path = target / f"{len(result['commands']) + 1:02d}.log"
        path.write_bytes(content)
        record["artifact"] = dict(path=path.relative_to(directory).as_posix(), sha256=hashlib.sha256(content).hexdigest())
        result["commands"].append(record)
        return stdout if record["return_code"] == 0 else None

    try:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        adb = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb") if sdk else None
        adb = str(adb) if adb is not None and adb.is_file() else shutil.which("adb")
        if adb is None:
            raise ValueError("Cannot locate adb for SystemUI diagnostics")
        devices = capture([adb, "devices"])
        if devices is None:
            raise ValueError("Cannot enumerate the blocked emulator")
        serials = [parts[0] for line in devices.splitlines() if len(parts := line.split()) >= 2
                   and re.fullmatch(r"emulator-\d+", parts[0]) and parts[1] == "device"]
        if len(serials) != 1:
            raise ValueError(f"Expected one online emulator; found {len(serials)}")
        result["serial"] = serials[0]
        selected = [adb, "-s", serials[0], "shell"]
        actual_api = capture(selected + ["getprop", "ro.build.version.sdk"])
        if actual_api is None or actual_api.strip() != str(api):
            raise ValueError(f"Blocked emulator API does not match expected API {api}: {actual_api!r}")
        for arguments in (["dumpsys", "activity", "lastanr"],
                          # Restored boot snapshots can retain a startup ANR dialog after lastanr was cleared.
                          ["dumpsys", "dropbox", "--print", "system_app_anr"],
                          ["logcat", "-d", "-v", "threadtime", "-b", "system", "-b", "events", "-b", "crash"],
                          ["dumpsys", "window"], ["dumpsys", "SurfaceFlinger"]):
            capture(selected + arguments)
    except (OSError, ValueError) as exc:
        result["capture_error"] = str(exc)
    finally:
        result["finished_at"] = timestamp()
        manifest = target / "manifest.json"
        content = (json.dumps(result, indent=2) + "\n").encode("utf-8")
        manifest.write_bytes(content)
    return dict(path=manifest.relative_to(directory).as_posix(), sha256=hashlib.sha256(content).hexdigest())


def collect_reports(repo, directory, module, api):
    reports, counts, observed = [], dict(total=0, failures=0, errors=0, skipped=0), set()
    # Pinned independently of discovery/results: disappearing tests cannot shrink the gate.
    inventory = json.loads(Path(__file__).with_name("instrumentation-inventory.json").read_text(encoding="utf-8"))
    required = {(suite, name.format(api=api)) for suite, names in inventory[module].items() for name in names}
    executed = {}
    target_module = "app" if module == "startup" else module
    source = repo / target_module / "build/outputs/androidTest-results/managedDevice/debug" / f"pixel2Api{api}"
    additional = repo / target_module / "build/outputs/managed_device_android_test_additional_output/debug" / f"pixel2Api{api}"
    report_files = sorted(source.glob("TEST-*.xml"))
    # Startup replaces AGP's device output, including UTP/command logs and binary results.
    # Snapshot the complete selected invocation before parsing any possibly malformed XML.
    report_set = set(report_files)
    diagnostic_files = sorted(path for root in (source, additional) for path in root.rglob("*")
                              if path.is_file() and path not in report_set and path.suffix != ".lck")
    merged = source.parent / "test-result.pb"
    if merged.is_file():
        diagnostic_files.append(merged)
    reports = [retain_artifact(repo, directory, module, path) for path in report_files]
    diagnostics = [retain_artifact(repo, directory, module, path) for path in diagnostic_files]
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{module}-artifacts.json").write_text(
        json.dumps(dict(reports=reports, diagnostic_artifacts=diagnostics), indent=2) + "\n", encoding="utf-8")
    for path in report_files:
        tree = ET.parse(path)
        if tree.getroot().tag not in ("testsuite", "testsuites"):
            raise ValueError(f"{module}: malformed JUnit root")
        cases = list(tree.iter("testcase"))
        counts["total"] += len(cases)
        for case in cases:
            identity = (case.get("classname"), case.get("name"))
            if not all(identity):
                raise ValueError(f"{module}: malformed JUnit testcase identity")
            executed.setdefault(identity, []).append(case)
            for tag, key in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                counts[key] += case.find(tag) is not None
            if (case.get("classname", "").endswith(".AndroidCompatibilityDeviceTest")
                    and case.get("name", "").startswith("recordsActualApiAndRejectsMismatchedRequestedTarget[")
                    and not any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))):
                observed.update(int(value) for value in re.findall(r"actualApi=(\d+)", case.get("name", "")))
        for suite in tree.iter():
            if suite.tag not in ("testsuite", "testsuites"):
                continue
            children = list(suite.iter("testcase"))
            actual = dict(tests=len(children), **{
                attribute: sum(case.find(tag) is not None for case in children)
                for tag, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped"))
            })
            for attribute, value in actual.items():
                declared = suite.get(attribute)
                if declared is not None and int(declared) != value:
                    raise ValueError(f"{module}: inconsistent JUnit {attribute} count")
    invalid = []
    for identity in sorted(required):
        matches = executed.get(identity, [])
        if len(matches) != 1 or any(matches[0].find(tag) is not None for tag in ("failure", "error", "skipped")):
            invalid.append("#".join(identity))
    if invalid:
        raise ValueError(f"{module}: {len(invalid)} missing, duplicate or unsuccessful mandatory testcases "
                         f"(first 20): {', '.join(invalid[:20])}")
    return dict(counts, observed_apis=sorted(observed), reports=reports, diagnostic_artifacts=diagnostics,
                mandatory_inventory_passed=True, mandatory_testcases=len(required))


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
            subprocess.run(sdkmanager_command(), check=True)
        for module in ("data", "app", "startup"):
            target_module = "app" if module == "startup" else module
            command = ["./gradlew", f":{target_module}:pixel2Api{args.api}DebugAndroidTest",
                       f"-Plifetracing.compatibility.expectedApi={args.api}",
                       "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect",
                       "-Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5",
                       "--no-parallel", "--max-workers=2", "--no-daemon", "--no-configuration-cache",
                       "--rerun-tasks", "--no-build-cache", "--console=plain", "--stacktrace"]
            if module == "startup":
                command.append("-Plifetracing.compatibility.productionStartup=true")
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
                            if ("Application Not Responding: com.android.systemui" in line
                                    and not record.get("system_ui_anr_observed")):
                                record["system_ui_anr_observed"] = True
                                log.flush()
                                try:
                                    record["system_ui_diagnostics"] = capture_system_ui_diagnostics(directory, module, args.api)
                                except (OSError, ValueError) as exc:
                                    record["system_ui_diagnostics_error"] = str(exc)
                        record["exit_code"] = process.wait()
            except (OSError, ValueError) as exc:
                record["execution_error"] = str(exc)
                print(f"::error::{module} API {args.api} command: {exc}", flush=True)
            # A command/log transport error must not bypass retention before startup.
            try:
                record.update(collect_reports(repo, directory, module, args.api))
            except (OSError, ET.ParseError, ValueError) as exc:
                record["evidence_error"] = str(exc)
                print(f"::error::{module} API {args.api}: {exc}", flush=True)
            record["finished_at"] = timestamp()
            passed = (record["exit_code"] == 0 and record.get("total", 0) > 0
                      and record.get("mandatory_inventory_passed") is True and "evidence_error" not in record
                      and "execution_error" not in record
                      and not record.get("system_ui_anr_observed", False)
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
