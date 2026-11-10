import importlib.util
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("instrumentation", Path(__file__).with_name("run-instrumentation.py"))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class InstrumentationEvidenceTests(unittest.TestCase):
    def successful_process(self, *args, **kwargs):
        return SimpleNamespace(stdout=io.StringIO(), wait=lambda timeout=None: 0)

    def setUp(self):
        # These orchestration/report tests supply fake Gradle processes. Real host-side
        # device admission and process deadlines are exercised separately below.
        health = patch.object(ci, "DeviceHealth", create=True)
        monitor = health.start().return_value
        monitor.inspect.return_value = None
        monitor.cleanup.return_value = True
        monitor.serial, monitor.avd, monitor.observations = None, None, []
        self.addCleanup(health.stop)

    def write_inventory(self, root, module="data", api=26):
        inventory = json.loads(Path(__file__).with_name("instrumentation-inventory.json").read_text())
        suite = ET.Element("testsuite")
        for classname, names in inventory[module].items():
            for name in names:
                ET.SubElement(suite, "testcase", classname=classname, name=name.format(api=api))
        target_module = "app" if module == "startup" else module
        source = root / target_module / f"build/outputs/androidTest-results/managedDevice/debug/pixel2Api{api}/TEST-suite.xml"
        source.parent.mkdir(parents=True, exist_ok=True)
        self.save_report(source, suite)
        return source, suite

    def save_report(self, source, suite):
        suite.set("tests", str(len(suite.findall("testcase"))))
        for tag, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            suite.set(attribute, str(len(suite.findall(f"testcase/{tag}"))))
        ET.ElementTree(suite).write(source, encoding="utf-8", xml_declaration=True)

    def test_marker_only_success_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, suite = self.write_inventory(root)
            for case in list(suite):
                if not case.get("name").startswith("recordsActualApi"):
                    suite.remove(case)
            self.save_report(source, suite)
            with self.assertRaisesRegex(ValueError, "mandatory"):
                ci.collect_reports(root, root / "evidence", "data", 26)

    def test_missing_mandatory_suite_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, suite = self.write_inventory(root)
            for case in list(suite):
                if case.get("classname").endswith(".ProductionDatabaseEntryPathTest"):
                    suite.remove(case)
            self.save_report(source, suite)
            with self.assertRaisesRegex(ValueError, "mandatory.*ProductionDatabaseEntryPathTest"):
                ci.collect_reports(root, root / "evidence", "data", 26)

    def test_missing_skipped_failed_error_duplicate_and_malformed_mandatory_cases_are_rejected(self):
        for module in ("data", "app", "startup"):
            for defect in ("missing", "skipped", "failure", "error", "duplicate", "malformed"):
                with self.subTest(module=module, defect=defect), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    source, suite = self.write_inventory(root, module)
                    case = suite.find("testcase")
                    if defect == "missing":
                        suite.remove(case)
                    elif defect == "duplicate":
                        suite.append(ET.fromstring(ET.tostring(case)))
                    elif defect == "malformed":
                        case.attrib.pop("name")
                    else:
                        ET.SubElement(case, defect)
                    self.save_report(source, suite)
                    with self.assertRaisesRegex(ValueError, "mandatory|malformed"):
                        ci.collect_reports(root, root / "evidence", module, 26)

    def test_complete_inventory_accepts_nonmandatory_ignored_fixtures_on_every_api(self):
        for module in ("data", "app", "startup"):
            for api in (26, 31, 33, 35, 36):
                with self.subTest(module=module, api=api), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    source, suite = self.write_inventory(root, module, api)
                    if module == "data":
                        for fixture in ("CurrentIndexIntegrity", "CurrentTableIntegrity", "HistoricalActiveV8Fixture",
                                        "HistoricalActiveV8State", "SequenceExecutionManualSchema"):
                            case = ET.SubElement(suite, "testcase", classname="fixture." + fixture, name="null")
                            ET.SubElement(case, "skipped")
                    self.save_report(source, suite)
                    result = ci.collect_reports(root, root / "evidence", module, api)
                    self.assertTrue(result["mandatory_inventory_passed"])
                    self.assertEqual(result["skipped"], 5 if module == "data" else 0)

    def test_original_report_and_observed_api_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, suite = self.write_inventory(root)
            result = ci.collect_reports(root, root / "evidence", "data", 26)
            self.assertEqual(result["observed_apis"], [26])
            self.assertEqual(result["total"], len(suite))
            self.assertEqual((root / "evidence" / source.relative_to(root)).read_bytes(), source.read_bytes())
            suite.set("tests", str(len(suite) + 1))
            ET.ElementTree(suite).write(source)
            with self.assertRaisesRegex(ValueError, "inconsistent JUnit"):
                ci.collect_reports(root, root / "evidence", "data", 26)

    def test_successful_gradle_with_marker_only_data_fails_but_app_is_still_certified(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, suite = self.write_inventory(root)
            self.write_inventory(root, "app")
            for case in list(suite):
                if not case.get("name").startswith("recordsActualApi"):
                    suite.remove(case)
            self.save_report(source, suite)
            original_collect = ci.collect_reports
            def launch(command, **kwargs):
                module = "startup" if "-Plifetracing.compatibility.productionStartup=true" in command else (
                    "data" if command[1].startswith(":data:") else "app")
                current, fixture = self.write_inventory(root, module)
                if module == "data":
                    for case in list(fixture):
                        if not case.get("name").startswith("recordsActualApi"):
                            fixture.remove(case)
                    self.save_report(current, fixture)
                return self.successful_process()
            def reports(repo, directory, module, api):
                if module == "startup":
                    self.write_inventory(repo, module, api)
                return original_collect(repo, directory, module, api)
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            with patch.object(ci, "collect_reports", side_effect=reports), \
                    patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                    patch.object(ci.subprocess, "run"), patch.object(ci.subprocess, "Popen") as worker, \
                    patch("sys.argv", ["ci", "--api", "26"]), contextlib.redirect_stdout(io.StringIO()):
                worker.side_effect = launch
                self.assertEqual(ci.main(), 1)
            evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["data"]["exit_code"], 0)
            self.assertIn("mandatory", evidence["modules"]["data"]["evidence_error"])
            self.assertTrue(evidence["modules"]["app"]["mandatory_inventory_passed"])
            self.assertEqual((root / "ci-evidence/api26" / source.relative_to(root)).read_bytes(), source.read_bytes())
            self.assertTrue(evidence["modules"]["startup"]["mandatory_inventory_passed"])
            self.assertEqual(worker.call_count, 3)

    def test_malformed_original_xml_is_retained_and_rejected(self):
        for malformed in (b"<testsuite>", b"<unknown/> "):
            with self.subTest(xml=malformed), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source, _ = self.write_inventory(root)
                source.write_bytes(malformed)
                with self.assertRaises((ValueError, ET.ParseError)):
                    ci.collect_reports(root, root / "evidence", "data", 26)
                self.assertEqual((root / "evidence" / source.relative_to(root)).read_bytes(), malformed)

    def test_data_failure_still_runs_app_sequentially_and_retains_exit_status(self):
        class Process:
            def __init__(self, command, **kwargs):
                commands.append(command)
                self.stdout = io.StringIO("> Task " + command[1] + " FAILED\n")
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self, timeout=None):
                return 1
        with tempfile.TemporaryDirectory() as temporary:
            commands = []
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager", "--install", "emulator"]), \
                    patch.object(ci.subprocess, "run") as install, \
                    patch.object(ci.subprocess, "Popen", Process), patch("sys.argv", ["ci", "--api", "26"]):
                self.assertEqual(ci.main(), 1)
            install.assert_called_once_with(["sdkmanager", "--install", "emulator"], check=True, timeout=180)
            self.assertEqual([c[1] for c in commands], [":data:pixel2Api26DebugAndroidTest", ":app:pixel2Api26DebugAndroidTest",
                                  ":app:pixel2Api26DebugAndroidTest"])
            evidence = json.loads((Path(temporary) / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["app"]["exit_code"], 1)
            self.assertNotEqual(evidence["event_sha"], evidence["tested_sha"])

    def test_command_io_failure_retains_app_artifacts_before_startup_and_rejects_gate(self):
        for phase in ("stream", "wait", "close"):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                originals, commands = {}, []
                fixture = self

                class Process:
                    def __init__(self, command, **kwargs):
                        commands.append(command)
                        self.module = ("startup" if "-Plifetracing.compatibility.productionStartup=true" in command
                                       else "data" if command[1].startswith(":data:") else "app")
                        source, suite = fixture.write_inventory(root, self.module, 35)
                        if self.module == "app":
                            if phase != "close":
                                ET.SubElement(suite.find("testcase"), "failure")
                                fixture.save_report(source, suite)
                            originals[source] = source.read_bytes()
                            for name, content in {
                                "utp.0.log": b"original runner interruption\n",
                                "test-result.pb": b"\x00\xfforiginal binary results",
                                "adb.023.instrument.fail.txt": b"original command stderr\n",
                            }.items():
                                path = source.with_name(name)
                                path.write_bytes(content)
                                originals[path] = content
                        elif self.module == "startup":
                            for path in originals:
                                if path != source:
                                    path.write_bytes(b"startup replacement\n")
                        self.returncode = 0
                        owner = self
                        class Stream:
                            def __iter__(self):
                                return owner.output()
                            def close(self):
                                if owner.module == "app" and phase == "close":
                                    raise OSError("app close interrupted")
                        self.stdout = Stream()

                    def output(self):
                        yield f"{self.module} command output\n"
                        if self.module == "app" and phase == "stream":
                            raise OSError("app stream interrupted")

                    def __enter__(self):
                        return self

                    def __exit__(self, *args):
                        if self.module == "app" and phase == "close":
                            raise OSError("app close interrupted")

                    def wait(self, timeout=None):
                        if self.module == "app" and phase == "wait":
                            raise OSError("app wait interrupted")
                        return 0

                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci.subprocess, "Popen", Process), \
                        patch.object(ci, "stop_process", return_value=True), \
                        patch("sys.argv", ["ci", "--api", "35"]), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(ci.main(), 1)

                directory = root / "ci-evidence/api35"
                for path, content in originals.items():
                    self.assertEqual((directory / path.relative_to(root)).read_bytes(), content)
                    self.assertNotEqual(path.read_bytes(), content)
                record = json.loads((directory / "evidence.json").read_text(encoding="utf-8"))["modules"]
                self.assertEqual(record["app"]["execution_error"], f"app {phase} interrupted")
                if phase == "close":
                    self.assertEqual(record["app"]["exit_code"], 0)
                    self.assertTrue(record["app"]["mandatory_inventory_passed"])
                else:
                    self.assertIn("mandatory", record["app"]["evidence_error"])
                self.assertTrue(record["startup"]["mandatory_inventory_passed"])
                self.assertEqual(len(commands), 3)
                self.assertIn("app command output", (directory / "app.log").read_text(encoding="utf-8"))

    def test_emulator_install_is_api26_only_and_precedes_both_full_suites(self):
        for api in (26, 31, 33, 35, 36):
            with self.subTest(api=api), tempfile.TemporaryDirectory() as temporary:
                calls = []
                def install(command, **kwargs):
                    calls.append(("install", command))
                def reports(*args):
                    calls.append(("suite", args[2]))
                    return dict(total=1, failures=0, errors=0, observed_apis=[api], reports=[], mandatory_inventory_passed=True)
                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci, "sdkmanager_command", return_value=["sdkmanager", "--install", "emulator"]), \
                        patch.object(ci.subprocess, "run", side_effect=install), \
                        patch.object(ci.subprocess, "Popen") as worker, \
                        patch.object(ci, "collect_reports", side_effect=reports), \
                        patch("sys.argv", ["ci", "--api", str(api)]):
                    worker.side_effect = self.successful_process
                    self.assertEqual(ci.main(), 0)
                self.assertEqual(calls, ([("install", ["sdkmanager", "--install", "emulator"])]
                                         if api == 26 else []) + [("suite", "data"), ("suite", "app"), ("suite", "startup")])
                commands = [call.args[0] for call in worker.call_args_list]
                self.assertEqual([c[1] for c in commands],
                                 [f":data:pixel2Api{api}DebugAndroidTest", f":app:pixel2Api{api}DebugAndroidTest",
                                  f":app:pixel2Api{api}DebugAndroidTest"])
                startup_property = "-Plifetracing.compatibility.productionStartup=true"
                self.assertTrue(all(startup_property not in c for c in commands[:2]))
                self.assertIn(startup_property, commands[2])
                self.assertTrue(all(not any("Arguments.class" in arg for arg in c) for c in commands))
                for command in commands:
                    graphics = [arg for arg in command if arg.startswith("-Pandroid.testoptions.manageddevices.emulator.gpu=")]
                    self.assertEqual(graphics, ["-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader"])
                    workers = [arg for arg in command if arg.startswith("--max-workers=")]
                    self.assertEqual(workers, ["--max-workers=1" if api == 36 else "--max-workers=2"])

    def test_emulator_install_failure_is_observable_and_keeps_failed_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager", "--install", "emulator"]), \
                    patch.object(ci.subprocess, "run", side_effect=ci.subprocess.CalledProcessError(1, "sdkmanager")), \
                    patch.object(ci.subprocess, "Popen") as worker, patch("sys.argv", ["ci", "--api", "26"]):
                with self.assertRaises(ci.subprocess.CalledProcessError):
                    ci.main()
                worker.assert_not_called()
            evidence = json.loads((Path(temporary) / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"], {})
            self.assertIn("finished_at", evidence)

    def test_api26_finds_sdkmanager_without_path_and_installs_into_the_configured_sdk(self):
        for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            with self.subTest(variable=variable), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sdk = root / "android sdk"
                manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
                manager.parent.mkdir(parents=True)
                manager.touch()
                command = [str(manager), f"--sdk_root={sdk}", "--install", "emulator"]
                def install(actual, **kwargs):
                    if actual[0] != str(manager):
                        raise FileNotFoundError("sdkmanager is not on PATH")
                    self.assertEqual(actual, command)
                    self.assertEqual(kwargs, {"check": True, "timeout": 180})
                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request", PATH="")
                env[variable] = str(sdk)
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env, clear=True), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci.shutil, "which", return_value=None), \
                        patch.object(ci.subprocess, "run", side_effect=install) as installation, \
                        patch.object(ci.subprocess, "Popen") as worker, \
                        patch.object(ci, "collect_reports", return_value=dict(
                            total=1, failures=0, errors=0, observed_apis=[26], reports=[], mandatory_inventory_passed=True)), \
                        patch("sys.argv", ["ci", "--api", "26"]):
                    worker.side_effect = self.successful_process
                    self.assertEqual(ci.main(), 0)
                installation.assert_called_once_with(command, check=True, timeout=180)
                self.assertEqual([call.args[0][1] for call in worker.call_args_list],
                                 [":data:pixel2Api26DebugAndroidTest", ":app:pixel2Api26DebugAndroidTest",
                                  ":app:pixel2Api26DebugAndroidTest"])
                evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
                self.assertEqual(set(evidence["modules"]), {"data", "app", "startup"})
                self.assertEqual(evidence["tested_sha"], "a" * 40)

    def test_sdkmanager_supports_installed_sdk_layouts_and_prefers_android_home(self):
        for layout in ("cmdline-tools/latest/bin", "cmdline-tools/19.0/bin", "tools/bin"):
            with self.subTest(layout=layout), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sdk = root / "sdk"
                manager = sdk / layout / "sdkmanager"
                manager.parent.mkdir(parents=True)
                manager.touch()
                env = dict(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(root / "other-sdk"))
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(ci.shutil, "which", return_value="/different-sdk/bin/sdkmanager"):
                    self.assertEqual(ci.sdkmanager_command(),
                                     [str(manager), f"--sdk_root={sdk}", "--install", "emulator"])

    def test_sdkmanager_path_fallback_preserves_an_explicit_sdk_root(self):
        for sdk_root in (None, "/configured/android sdk"):
            with self.subTest(sdk_root=sdk_root):
                env = {} if sdk_root is None else {"ANDROID_SDK_ROOT": sdk_root}
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(ci.Path, "is_file", return_value=False), \
                        patch.object(ci.shutil, "which", return_value="/tools/bin/sdkmanager"):
                    self.assertEqual(ci.sdkmanager_command(), ["/tools/bin/sdkmanager"] +
                                     ([f"--sdk_root={sdk_root}"] if sdk_root else []) + ["--install", "emulator"])

    def test_missing_sdkmanager_fails_with_actionable_error_and_preserves_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request", PATH="")
            with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env, clear=True), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci.shutil, "which", return_value=None), \
                    patch.object(ci.subprocess, "run") as installation, \
                    patch.object(ci.subprocess, "Popen") as worker, patch("sys.argv", ["ci", "--api", "26"]):
                with self.assertRaisesRegex(FileNotFoundError, "ANDROID_HOME.*ANDROID_SDK_ROOT.*PATH"):
                    ci.main()
                installation.assert_not_called()
                worker.assert_not_called()
            evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"], {})
            self.assertIn("finished_at", evidence)

    def test_failed_startup_rejects_gate_even_when_both_full_suites_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            def reports(repo, directory, module, api):
                return dict(total=2, failures=int(module == "startup"), errors=0,
                            observed_apis=[api], reports=[], mandatory_inventory_passed=module != "startup")
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci.subprocess, "Popen") as worker, \
                    patch.object(ci, "collect_reports", side_effect=reports), \
                    patch("sys.argv", ["ci", "--api", "35"]), contextlib.redirect_stdout(io.StringIO()):
                worker.side_effect = self.successful_process
                self.assertEqual(ci.main(), 1)
            evidence = json.loads((Path(temporary) / "ci-evidence/api35/evidence.json").read_text())
            self.assertTrue(evidence["modules"]["data"]["mandatory_inventory_passed"])
            self.assertTrue(evidence["modules"]["app"]["mandatory_inventory_passed"])
            self.assertFalse(evidence["modules"]["startup"]["mandatory_inventory_passed"])

    def test_known_successful_api26_timeline_fits_shared_execution_budget(self):
        # CI387 attempt2: data 30:29, app 17:33, startup 2:34; row 50:46.
        with tempfile.TemporaryDirectory() as temporary:
            clock = [0.0]
            durations = {"data": 1830, "app": 1054, "startup": 154}
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="2", GITHUB_EVENT_NAME="pull_request")
            def run(command, directory, module, api, record, *, timeout):
                record.update(exit_code=0, process_cleanup_passed=True, device_cleanup_passed=True)
                if durations[module] > timeout:
                    record.update(exit_code=143, abort_reason="execution deadline exceeded")
                else:
                    self.write_inventory(Path(temporary), module, api)
                clock[0] += durations[module]
            def provision(*args, **kwargs):
                clock[0] += 11
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci.subprocess, "run", side_effect=provision), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                    patch.object(ci.time, "monotonic", side_effect=lambda: clock[0]), \
                    patch.object(ci, "run_gradle", side_effect=run) as worker, \
                    patch("sys.argv", ["ci", "--api", "26"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ci.main(), 0)
            self.assertEqual(worker.call_count, 3)
            modules = json.loads((Path(temporary) / "ci-evidence/api26/evidence.json").read_text())["modules"]
            self.assertEqual([r["status"] for r in modules.values()], ["PASSED"] * 3)

    def test_api26_extended_budget_is_shared_and_still_blocks_after_expiry(self):
        # Regression for CI41493383857: preparation/data consume ~2055 s before app.
        # The synthetic app/startup durations test the budget, not a CI timing prediction.
        for app_seconds, expected_result in ((1800, 0), (5000, 1)):
            with self.subTest(app_seconds=app_seconds), tempfile.TemporaryDirectory() as temporary:
                root, clock, allowances = Path(temporary), [0.0], []
                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                def provision(*args, **kwargs):
                    clock[0] += 150
                def run(command, directory, module, api, record, *, timeout):
                    allowances.append((module, timeout))
                    duration = {"data": 1905, "app": app_seconds, "startup": 180}[module]
                    record.update(exit_code=0, process_cleanup_passed=True, device_cleanup_passed=True)
                    if duration > timeout:
                        record.update(exit_code=143, abort_reason="execution deadline exceeded")
                    else:
                        self.write_inventory(root, module, api)
                    clock[0] += min(duration, timeout)
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci.subprocess, "run", side_effect=provision), \
                        patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                        patch.object(ci.time, "monotonic", side_effect=lambda: clock[0]), \
                        patch.object(ci, "run_gradle", side_effect=run), \
                        patch("sys.argv", ["ci", "--api", "26"]), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(ci.main(), expected_result)
                self.assertEqual(allowances[:2], [("data", 6450), ("app", 4545)])
                evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
                if expected_result:
                    self.assertEqual(len(allowances), 2)
                    self.assertEqual(evidence["modules"]["startup"]["status"], "NOT_RUN")
                else:
                    self.assertEqual(allowances[2], ("startup", 2745))
                    self.assertTrue(all(r["mandatory_inventory_passed"] for r in evidence["modules"].values()))

    def test_api35_and_api36_full_inventory_share_extended_bounded_budget(self):
        # CI42726834230 exhausted 3300 s after data + 410/584 (API35) or 401/584
        # (API36) app cases. Synthetic durations cross that bound and leave startup room.
        for api in (35, 36):
            for app_seconds, result in ((3600, 0), (6000, 1)):
                with self.subTest(api=api, app_seconds=app_seconds), tempfile.TemporaryDirectory() as temporary:
                    root, clock, allowances = Path(temporary), [0.0], []
                    env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                               GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                    def run(command, directory, module, actual_api, record, *, timeout):
                        self.assertEqual(actual_api, api)
                        self.assertIn(f"--max-workers={1 if api == 36 else 2}", command)
                        self.assertFalse(any("class=" in arg for arg in command))
                        self.assertEqual("-Plifetracing.compatibility.productionStartup=true" in command,
                                         module == "startup")
                        allowances.append((module, timeout))
                        duration = {"data": 1000, "app": app_seconds, "startup": 180}[module]
                        record.update(exit_code=0, process_cleanup_passed=True, device_cleanup_passed=True)
                        if duration > timeout:
                            record.update(exit_code=143, abort_reason="execution deadline exceeded")
                        else:
                            self.write_inventory(root, module, api)
                        clock[0] += min(duration, timeout)
                    with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                            patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                            patch.object(ci.time, "monotonic", side_effect=lambda: clock[0]), \
                            patch.object(ci, "run_gradle", side_effect=run), \
                            patch("sys.argv", ["ci", "--api", str(api)]), contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(ci.main(), result)
                    self.assertEqual(allowances[:2], [("data", 6600), ("app", 5600)])
                    evidence = json.loads((root / f"ci-evidence/api{api}/evidence.json").read_text())
                    self.assertEqual(evidence["row_timeout_seconds"], 6600)
                    if result:
                        self.assertEqual(len(allowances), 2)
                        self.assertEqual(evidence["modules"]["app"]["abort_reason"], "execution deadline exceeded")
                        self.assertEqual(evidence["modules"]["startup"]["status"], "NOT_RUN")
                    else:
                        self.assertEqual(allowances[2], ("startup", 2000))
                        self.assertTrue(all(r["mandatory_inventory_passed"] for r in evidence["modules"].values()))
                        self.assertEqual([r["status"] for r in evidence["modules"].values()], ["PASSED"] * 3)

    def test_workflow_extended_rows_keep_ten_minutes_for_setup_cleanup_and_upload(self):
        workflow = Path(__file__).parents[1] / "workflows/ci.yml"
        self.assertIn("timeout-minutes: ${{ (matrix.api == 26 || matrix.api == 35 || matrix.api == 36) && 120 || 60 }}",
                      workflow.read_text(encoding="utf-8"))

    def test_expired_row_budget_never_launches_or_certifies_remaining_invocations(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci.subprocess, "Popen") as worker, \
                    patch.object(ci.time, "monotonic", side_effect=[0, 6601]), \
                    patch("sys.argv", ["ci", "--api", "36"]):
                self.assertEqual(ci.main(), 1)
            worker.assert_not_called()
            modules = json.loads((Path(temporary) / "ci-evidence/api36/evidence.json").read_text())["modules"]
            self.assertEqual(set(modules), {"data", "app", "startup"})
            for record in modules.values():
                self.assertEqual(record["status"], "NOT_RUN")
                self.assertIsNone(record["exit_code"])
                self.assertFalse(record["mandatory_inventory_passed"])

    def test_failed_cleanup_blocks_siblings_even_when_exit_and_inventory_are_green(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            def run(command, directory, module, api, record, **kwargs):
                record.update(exit_code=0, process_cleanup_passed=False, device_cleanup_passed=True)
                self.write_inventory(directory.parent.parent, module, api)
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "run_gradle", side_effect=run) as worker, \
                    patch("sys.argv", ["ci", "--api", "36"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ci.main(), 1)
            self.assertEqual(worker.call_count, 1)
            modules = json.loads((Path(temporary) / "ci-evidence/api36/evidence.json").read_text())["modules"]
            self.assertTrue(modules["data"]["mandatory_inventory_passed"])
            self.assertEqual(modules["data"]["status"], "FAILED")
            for module in ("app", "startup"):
                self.assertEqual(modules[module]["status"], "NOT_RUN")
                self.assertEqual(modules[module]["blocked_by"], "data")

    def test_startup_evidence_does_not_overwrite_full_app_original_xml(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, _ = self.write_inventory(root, "app")
            original = source.read_bytes()
            ci.collect_reports(root, root / "evidence", "app", 26)
            self.write_inventory(root, "startup")
            ci.collect_reports(root, root / "evidence", "startup", 26)
            self.assertEqual((root / "evidence" / source.relative_to(root)).read_bytes(), original)
            self.assertEqual((root / "evidence/startup" / source.relative_to(root)).read_bytes(), source.read_bytes())

    def test_native_diagnostics_survive_startup_output_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, _ = self.write_inventory(root, "app", 36)
            originals = {
                "logcat-FailingRouteTest-nativeWindow.txt": b"full app window focus diagnostic\n",
                "adb.021.logcat.aborted.txt": b"full app system logcat\n" * 100_000 + b"final focus owner\x00\xff\n",
                "utp.0.log": b"original runner failure\n",
                "test-result.pb": b"\x00\xfforiginal binary result\n",
                "testlog/test-results.log": b"original instrumentation status\n",
                "adb.023.instrument.fail.txt": b"original command and stderr\n",
            }
            for name, content in originals.items():
                (source.parent / name).parent.mkdir(parents=True, exist_ok=True)
                (source.parent / name).write_bytes(content)
            unrelated = source.with_name("utp.0.log.lck")
            unrelated.write_text("unrelated output", encoding="utf-8")
            other_api = source.parent.with_name("pixel2Api35") / "logcat-other.txt"
            other_api.parent.mkdir()
            other_api.write_text("other API", encoding="utf-8")

            evidence = root / "evidence"
            result = ci.collect_reports(root, evidence, "app", 36)
            self.write_inventory(root, "startup", 36)
            for name in originals:
                (source.parent / name).write_bytes(b"startup logcat\n")
            ci.collect_reports(root, evidence, "startup", 36)

            for name, content in originals.items():
                relative = (source.parent / name).relative_to(root)
                self.assertEqual((evidence / relative).read_bytes(), content)
                self.assertEqual((evidence / "startup" / relative).read_bytes(), b"startup logcat\n")
            self.assertEqual(len(result["reports"]), 1)
            self.assertEqual(len(result["diagnostic_artifacts"]), len(originals))
            hashes = {item["path"]: item["sha256"] for item in result["diagnostic_artifacts"]}
            for name, content in originals.items():
                relative = (source.parent / name).relative_to(root).as_posix()
                self.assertEqual(hashes[relative], hashlib.sha256(content).hexdigest())
            self.assertFalse((evidence / unrelated.relative_to(root)).exists())
            self.assertFalse((evidence / other_api.relative_to(root)).exists())

    def test_all_original_xml_and_runner_evidence_survive_malformed_first_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, _ = self.write_inventory(root, "app", 26)
            malformed = source.with_name("TEST-0-malformed.xml")
            malformed.write_bytes(b"<testsuite>")
            runner = source.with_name("utp.0.log")
            runner.write_bytes(b"Test run failed to complete: expected 510, received 314\n")
            merged = source.parent.parent / "test-result.pb"
            merged.write_bytes(b"\x00\xfforiginal merged result")
            native = root / "app/build/outputs/managed_device_android_test_additional_output/debug/pixel2Api26/crash/window.png"
            native.parent.mkdir(parents=True)
            native.write_bytes(b"\x89PNG\x00original native output")
            original = source.read_bytes()
            evidence = root / "evidence"
            with self.assertRaises(ET.ParseError):
                ci.collect_reports(root, evidence, "app", 26)
            self.assertEqual((evidence / source.relative_to(root)).read_bytes(), original)
            self.assertEqual((evidence / runner.relative_to(root)).read_bytes(), runner.read_bytes())
            self.assertEqual((evidence / merged.relative_to(root)).read_bytes(), merged.read_bytes())
            self.assertEqual((evidence / native.relative_to(root)).read_bytes(), native.read_bytes())
            manifest = json.loads((evidence / "app-artifacts.json").read_text(encoding="utf-8"))
            self.assertEqual(len(manifest["reports"]), 2)
            self.assertEqual(len(manifest["diagnostic_artifacts"]), 3)

    def test_agp_intermediate_diagnostic_artifacts_survive_startup_and_are_quarantined(self):
        for module in ("data", "app", "startup"):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.write_inventory(root, module, 35)
                target = "app" if module == "startup" else module
                base = root / target / "build/intermediates/managed_device_android_test_additional_output/debugAndroidTest"
                capture = base / "pixel2Api35DebugAndroidTest/native-window-diagnostics/capture.txt"
                capture.parent.mkdir(parents=True)
                original = ("Ж🙂\n" * 90_000 + "final marker\n").encode("utf-8")
                capture.write_bytes(original)
                receipt = capture.with_suffix(".txt.receipt")
                receipt.write_bytes(b"acknowledged=616\n")
                other_api = base / "pixel2Api36DebugAndroidTest/native-window-diagnostics/capture.txt"
                other_api.parent.mkdir(parents=True)
                other_api.write_bytes(b"other API")
                evidence = root / "evidence"
                result = ci.collect_reports(root, evidence, module, 35)
                prefix = Path("startup") if module == "startup" else Path()
                retained = prefix / capture.relative_to(root)
                self.assertEqual((evidence / retained).read_bytes(), original)
                hashes = {a["path"]: a["sha256"] for a in result["diagnostic_artifacts"]}
                self.assertEqual(hashes[retained.as_posix()], hashlib.sha256(original).hexdigest())
                self.assertEqual((evidence / prefix / receipt.relative_to(root)).read_bytes(), receipt.read_bytes())
                self.assertFalse((evidence / prefix / other_api.relative_to(root)).exists())
                if module == "app":
                    self.write_inventory(root, "startup", 35)
                    capture.write_bytes(b"startup replacement")
                    ci.collect_reports(root, evidence, "startup", 35)
                    self.assertEqual((evidence / retained).read_bytes(), original)
                    self.assertEqual((evidence / "startup" / capture.relative_to(root)).read_bytes(), b"startup replacement")
                current = capture.read_bytes()
                ci.quarantine_previous_outputs(root, evidence, module, 35)
                self.assertFalse(capture.exists())
                self.assertEqual((evidence / "previous" / module / capture.relative_to(root)).read_bytes(), current)
                self.assertTrue(other_api.exists())
                with self.assertRaisesRegex(ValueError, "mandatory"):
                    ci.collect_reports(root, root / "failed-build", module, 35)
                manifest = json.loads((root / "failed-build" / f"{module}-artifacts.json").read_text(encoding="utf-8"))
                self.assertEqual(manifest, dict(reports=[], diagnostic_artifacts=[]))

    def test_empty_device_output_is_rejected_with_empty_artifact_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            with self.assertRaisesRegex(ValueError, "mandatory"):
                ci.collect_reports(root, evidence, "app", 26)
            manifest = json.loads((evidence / "app-artifacts.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest, dict(reports=[], diagnostic_artifacts=[]))

    def test_native_diagnostics_survive_missing_malformed_or_unsuccessful_reports(self):
        for defect in ("missing", "malformed", "failure"):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source, suite = self.write_inventory(root, "app", 36)
                diagnostic = source.with_name("logcat-FailingRouteTest-nativeWindow.txt")
                content = b"native-window evidence must survive a failing invocation\n"
                diagnostic.write_bytes(content)
                if defect == "missing":
                    source.unlink()
                elif defect == "malformed":
                    source.write_bytes(b"<testsuite>")
                else:
                    ET.SubElement(suite.find("testcase"), "failure")
                    self.save_report(source, suite)
                with self.assertRaises((ValueError, ET.ParseError)):
                    ci.collect_reports(root, root / "evidence", "app", 36)
                self.assertEqual((root / "evidence" / diagnostic.relative_to(root)).read_bytes(), content)

    def test_system_ui_anr_captures_once_blocks_startup_and_cannot_pass_green_reports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            adb.parent.mkdir(parents=True)
            adb.touch()
            directory = root / "ci-evidence/api36"
            fixture = self

            class Process:
                def __init__(self, command, **kwargs):
                    module = ("startup" if "-Plifetracing.compatibility.productionStartup=true" in command
                              else "data" if command[1].startswith(":data:") else "app")
                    if module == "startup":
                        fixture.assertTrue((directory / "app-system-ui/manifest.json").is_file())
                    fixture.write_inventory(root, module, 36)
                    self.stdout = io.StringIO("mCurrentFocus=Window{abc Application Not Responding: com.android.systemui}\n" * 2
                                              if module == "app" else "")
                    self.returncode = -15 if module == "app" else 0

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    pass

                def wait(self, timeout=None):
                    return 0

            def capture(command, **kwargs):
                self.assertEqual(kwargs["timeout"], 5)
                self.assertEqual(command[0], str(adb))
                if command[1:] == ["devices"]:
                    output = "List of devices attached\nemulator-5554\tdevice\nphysical-device\tdevice\n"
                else:
                    self.assertEqual(command[1:3], ["-s", "emulator-5554"])
                    output = "36\n" if "getprop" in command else "ANR in com.android.systemui\noriginal system state\n"
                return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request", ANDROID_HOME=str(sdk))
            with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci.subprocess, "Popen", Process), \
                    patch.object(ci, "stop_process", return_value=True), \
                    patch.object(ci.subprocess, "run", side_effect=capture) as captured, \
                    patch("sys.argv", ["ci", "--api", "36"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ci.main(), 1)
            app = json.loads((directory / "evidence.json").read_text(encoding="utf-8"))["modules"]["app"]
            self.assertTrue(app["system_ui_anr_observed"])
            self.assertTrue(app["mandatory_inventory_passed"])
            self.assertEqual(app["failures"], 0)
            modules = json.loads((directory / "evidence.json").read_text(encoding="utf-8"))["modules"]
            self.assertEqual(modules["startup"]["status"], "NOT_RUN")
            self.assertFalse(modules["startup"]["mandatory_inventory_passed"])
            self.assertEqual(modules["startup"]["blocked_by"], "app")
            self.assertEqual(captured.call_count, 7)
            diagnostics = json.loads((directory / "app-system-ui/manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(diagnostics["serial"], "emulator-5554")
            for record in diagnostics["commands"]:
                content = (directory / record["artifact"]["path"]).read_bytes()
                self.assertEqual(record["artifact"]["sha256"], hashlib.sha256(content).hexdigest())
            self.assertTrue(any("lastanr" in c.args[0] for c in captured.call_args_list))
            self.assertTrue(any("system_app_anr" in c.args[0] for c in captured.call_args_list))
            self.assertTrue(any("system" in c.args[0] and "events" in c.args[0] for c in captured.call_args_list))

    def test_dead_system_capture_precedes_cleanup_runs_once_and_cannot_certify_green_reports(self):
        for capture_fails in (False, True):
            with self.subTest(capture_fails=capture_fails), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = root / "ci-evidence/api26"
                fixture, invocations, captures = self, [], []

                class Process:
                    def __init__(self, command, **kwargs):
                        module = ("startup" if "-Plifetracing.compatibility.productionStartup=true" in command
                                  else "data" if command[1].startswith(":data:") else "app")
                        invocations.append(module)
                        fixture.write_inventory(root, module, 26)
                        self.stdout = io.StringIO("java.lang.RuntimeException: android.os.DeadSystemException\n" * 2
                                                  + "original output after crash\n" if module == "app" else "")
                        self.returncode = 0  # Contradictory green reports must not erase the observed system death.

                    def wait(self, timeout=None):
                        return self.returncode

                def capture(target, module, api, **kwargs):
                    self.assertEqual((target, module, api, kwargs),
                                     (directory, "app", 26, dict(native_crash=True)))
                    captures.append(module)
                    if capture_fails:
                        raise OSError("native capture unavailable")
                    path = target / "app-native-crash/manifest.json"
                    path.parent.mkdir(parents=True)
                    path.write_text("original crash evidence", encoding="utf-8")
                    return dict(path="app-native-crash/manifest.json", sha256=hashlib.sha256(path.read_bytes()).hexdigest())

                def cleanup():
                    if invocations[-1] == "app":
                        self.assertEqual(captures, ["app"], "Capture must precede owned-process/device teardown")
                    return True

                with patch.object(ci.Path, "cwd", return_value=root), \
                        patch.dict(os.environ, dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40,
                                                    GITHUB_RUN_ID="7", GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci.subprocess, "Popen", Process), \
                        patch.object(ci.subprocess, "run", return_value=ci.subprocess.CompletedProcess([], 0)), \
                        patch.object(ci, "OwnedProcesses", return_value=SimpleNamespace(cleanup=cleanup)), \
                        patch.object(ci, "capture_system_ui_diagnostics", side_effect=capture), \
                        patch("sys.argv", ["ci", "--api", "26"]), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(ci.main(), 1)
                modules = json.loads((directory / "evidence.json").read_text(encoding="utf-8"))["modules"]
                self.assertEqual(invocations, ["data", "app", "startup"])
                app = modules["app"]
                self.assertTrue(app["native_system_crash_observed"])
                self.assertEqual(app["status"], "FAILED")
                self.assertEqual(app["exit_code"], 0)
                self.assertTrue(app["mandatory_inventory_passed"])
                self.assertEqual(app["failures"], 0)
                self.assertEqual(modules["startup"]["status"], "PASSED")
                self.assertIn("original output after crash", (directory / "app.log").read_text(encoding="utf-8"))
                if capture_fails:
                    self.assertEqual(app["native_crash_diagnostics_error"], "native capture unavailable")
                else:
                    self.assertEqual(app["native_crash_diagnostics"]["path"], "app-native-crash/manifest.json")

    def test_native_crash_capture_retains_tombstones_errors_and_all_system_buffers(self):
        for outcome in ("available", "denied", "timeout"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                original = "pid: 2560, tid: 2606, name: android.anim >>> system_server <<<\nfull tombstone maps\n"

                def capture(command, **kwargs):
                    self.assertEqual(kwargs["timeout"], 5)
                    if command[1:] == ["devices"]:
                        output = "emulator-5554\tdevice\n"
                    elif "getprop" in command:
                        output = "26\n"
                    elif "su" in command:
                        self.assertEqual(command[-5:], ["su", "0", "sh", "-c", "'cat /data/tombstones/tombstone_*'"])
                        if outcome == "timeout":
                            raise ci.subprocess.TimeoutExpired(command, 5, output=original.encode(), stderr=b"partial error")
                        if outcome == "denied":
                            return ci.subprocess.CompletedProcess(command, 1, stdout="", stderr="Permission denied")
                        output = original
                    else:
                        output = "persisted crash evidence\n"
                    return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

                with patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}), \
                        patch.object(ci.shutil, "which", return_value="test-adb"), \
                        patch.object(ci.subprocess, "run", side_effect=capture) as captured:
                    reference = ci.capture_system_ui_diagnostics(directory, "app", 26, native_crash=True)
                content = (directory / reference["path"]).read_bytes()
                self.assertEqual(reference["sha256"], hashlib.sha256(content).hexdigest())
                manifest = json.loads(content)
                self.assertEqual(captured.call_count, 6)
                commands = manifest["commands"]
                self.assertEqual([r["command"][-1] for r in commands if "dropbox" in r["command"]],
                                 ["SYSTEM_TOMBSTONE", "system_server_native_crash"])
                logcat = next(r["command"] for r in commands if "logcat" in r["command"])
                self.assertEqual(logcat[-8:], ["-b", "main", "-b", "system", "-b", "events", "-b", "crash"])
                for record in commands:
                    self.assertEqual(record["artifact"]["sha256"], hashlib.sha256(
                        (directory / record["artifact"]["path"]).read_bytes()).hexdigest())
                tombstone = next(r for r in commands if "su" in r["command"])
                retained = (directory / tombstone["artifact"]["path"]).read_text(encoding="utf-8")
                if outcome == "denied":
                    self.assertEqual(tombstone["return_code"], 1)
                    self.assertIn("Permission denied", retained)
                else:
                    self.assertIn(original, retained)
                    if outcome == "timeout":
                        self.assertIn("error", tombstone)
                        self.assertIn("partial error", retained)

    def test_system_ui_capture_preserves_persistent_startup_anr_when_lastanr_is_empty(self):
        for outcome in ("report", "denied", "timeout"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                startup_report = ("system_app_anr\nProcess: com.android.systemui\n"
                                  "Subject: Process failed to complete startup\n"
                                  '"main" prio=5 tid=1 Blocked\n  at startup.waitForService(Native method)\n')

                def capture(command, **kwargs):
                    self.assertEqual(kwargs["timeout"], 5)
                    if command[1:] == ["devices"]:
                        output = "emulator-5554\tdevice\n"
                    elif "getprop" in command:
                        output = "36\n"
                    elif "lastanr" in command:
                        output = "ACTIVITY MANAGER LAST ANR\n  <no ANR has occurred since boot>\n"
                    elif "dropbox" in command:
                        self.assertEqual(command[-4:], ["dumpsys", "dropbox", "--print", "system_app_anr"])
                        if outcome == "timeout":
                            raise ci.subprocess.TimeoutExpired(command, 5, output=startup_report.encode("utf-8"))
                        if outcome == "denied":
                            return ci.subprocess.CompletedProcess(command, 1, stdout="", stderr="Permission denied")
                        output = startup_report
                    else:
                        output = "remaining platform state\n"
                    return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

                with patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}), \
                        patch.object(ci.shutil, "which", return_value="test-adb"), \
                        patch.object(ci.subprocess, "run", side_effect=capture):
                    reference = ci.capture_system_ui_diagnostics(directory, "app", 36)
                content = (directory / reference["path"]).read_bytes()
                self.assertEqual(reference["sha256"], hashlib.sha256(content).hexdigest())
                manifest = json.loads(content)
                reports = [r for r in manifest["commands"] if "dropbox" in r["command"]]
                self.assertEqual(len(reports), 1, "The saved startup ANR stack must be requested even with empty lastanr")
                record = reports[0]
                report = (directory / record["artifact"]["path"]).read_bytes()
                self.assertEqual(record["artifact"]["sha256"], hashlib.sha256(report).hexdigest())
                if outcome == "denied":
                    self.assertEqual(record["return_code"], 1)
                    self.assertIn(b"Permission denied", report)
                else:
                    self.assertIn(startup_report.encode("utf-8"), report)
                    if outcome == "timeout":
                        self.assertIn("error", record)
                        self.assertIsNone(record["return_code"])
                self.assertTrue(any("window" in r["command"] for r in manifest["commands"]))
                self.assertTrue(any("SurfaceFlinger" in r["command"] for r in manifest["commands"]))

    def test_system_ui_capture_refuses_missing_ambiguous_or_wrong_api_emulators(self):
        for devices, actual_api, calls in [
            ("physical-device\tdevice\n", None, 1),
            ("emulator-5554\tdevice\nemulator-5556\tdevice\n", None, 1),
            ("emulator-5554\toffline\n", None, 1),
            ("emulator-5554\tdevice\n", "35\n", 2),
        ]:
            for native_crash in (False, True):
                with self.subTest(devices=devices, api=actual_api, native_crash=native_crash), \
                        tempfile.TemporaryDirectory() as temporary:
                    directory = Path(temporary)
                    replies = [ci.subprocess.CompletedProcess([], 0, stdout=devices, stderr="")]
                    if actual_api:
                        replies.append(ci.subprocess.CompletedProcess([], 0, stdout=actual_api, stderr=""))
                    with patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}), \
                            patch.object(ci.shutil, "which", return_value="test-adb"), \
                            patch.object(ci.subprocess, "run", side_effect=replies) as captured:
                        reference = ci.capture_system_ui_diagnostics(directory, "app", 36, native_crash=native_crash)
                    manifest = json.loads((directory / reference["path"]).read_text(encoding="utf-8"))
                    self.assertIn("capture_error", manifest)
                    self.assertEqual(captured.call_count, calls)

    def test_system_ui_capture_retains_timeout_output_and_continues_other_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def capture(command, **kwargs):
                if command[1:] == ["devices"]:
                    return ci.subprocess.CompletedProcess(command, 0, stdout="emulator-5554\tdevice\n", stderr="")
                if "getprop" in command:
                    return ci.subprocess.CompletedProcess(command, 0, stdout="36", stderr="")
                if "lastanr" in command:
                    raise ci.subprocess.TimeoutExpired(command, 5, output=b"partial ANR reason", stderr=b"partial stderr")
                if "SurfaceFlinger" in command:
                    raise OSError("capture unavailable")
                return ci.subprocess.CompletedProcess(command, 0, stdout="remaining diagnostic", stderr="")
            with patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}), \
                    patch.object(ci.shutil, "which", return_value="test-adb"), \
                    patch.object(ci.subprocess, "run", side_effect=capture) as captured:
                ci.capture_system_ui_diagnostics(directory, "app", 36)
            manifest = json.loads((directory / "app-system-ui/manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(captured.call_count, 7)
            timed_out = next(r for r in manifest["commands"] if "lastanr" in r["command"])
            self.assertIn("error", timed_out)
            partial = (directory / timed_out["artifact"]["path"]).read_text(encoding="utf-8")
            self.assertIn("partial ANR reason", partial)
            self.assertIn("partial stderr", partial)
            self.assertTrue(any("SurfaceFlinger" in r["command"] and "error" in r for r in manifest["commands"]))

    def test_merge_checkout_is_rejected_before_running_gradle(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), \
                    patch.dict(os.environ, {"EXPECTED_TEST_SHA": "a" * 40}), \
                    patch.object(ci.subprocess, "check_output", return_value="b" * 40), \
                    patch.object(ci.subprocess, "Popen") as worker, patch("sys.argv", ["ci", "--api", "26"]):
                with self.assertRaisesRegex(RuntimeError, "Checkout mismatch"):
                    ci.main()
                worker.assert_not_called()


class InstrumentationAdmissionTests(unittest.TestCase):
    def test_ci_boot_probe_failures_can_recover_without_aborting_the_invocation(self):
        # CI #389: API26/31/33 closed getprop; API36 timed it out; API35 timed out window.
        for probe, failure in (("ro.build.version.sdk", "closed"),
                               ("ro.build.version.sdk", "timeout"), ("window", "timeout")):
            for previously_ready in (False, True):
                with self.subTest(probe=probe, failure=failure, previously_ready=previously_ready):
                    clock, mode, calls = [0], ["healthy"], []
                    def adb(command, **kwargs):
                        calls.append(command)
                        if command[-1] == "devices":
                            output = "" if len(calls) == 1 else "emulator-5554\tdevice\n"
                        elif command[-1] == probe and mode[0] == "failing":
                            if failure == "timeout":
                                raise ci.subprocess.TimeoutExpired(command, 5)
                            return ci.subprocess.CompletedProcess(command, 1, stdout="", stderr="error: closed\n")
                        elif command[-1] == "ro.build.version.sdk":
                            output = "36\n"
                        elif command[-1] == "sys.boot_completed":
                            output = "1\n" if mode[0] == "healthy" else "\n"
                        elif command[-3:] == ["emu", "avd", "name"]:
                            output = "managed-avd\nOK\n"
                        else:
                            output = ""
                        return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")
                    with patch.object(ci, "adb_command", return_value="adb"), \
                            patch.object(ci.subprocess, "run", side_effect=adb), \
                            patch.object(ci.time, "monotonic", side_effect=lambda: clock[0]):
                        monitor = ci.DeviceHealth(36)
                        if previously_ready:
                            self.assertIsNone(monitor.inspect())
                        mode[0] = "failing"
                        self.assertIsNone(monitor.inspect(), "A single probe failure is not a fatal device verdict")
                        clock[0], mode[0] = 10, "healthy"
                        self.assertIsNone(monitor.inspect())
                        self.assertEqual(monitor.avd, "managed-avd")

    def test_unavailable_device_has_bounded_recovery_and_healthy_probe_resets_it(self):
        clock, available = [0], [False]
        def read(arguments):
            if arguments == ["devices"]:
                return "emulator-5554\tdevice\n"
            if not available[0]:
                raise ci.subprocess.TimeoutExpired(arguments, 5)
            if arguments[-1] == "ro.build.version.sdk":
                return "36\n"
            if arguments[-1] == "sys.boot_completed":
                return "1\n"
            if arguments[-3:] == ["emu", "avd", "name"]:
                return "managed-avd\nOK\n"
            return ""
        with patch.object(ci, "adb_command", return_value="adb"), \
                patch.object(ci.DeviceHealth, "devices", return_value=[]):
            monitor = ci.DeviceHealth(36)
        with patch.object(monitor, "read", side_effect=read), \
                patch.object(ci.time, "monotonic", side_effect=lambda: clock[0]):
            self.assertIsNone(monitor.inspect())
            clock[0], available[0] = 299, True
            self.assertIsNone(monitor.inspect())
            clock[0], available[0] = 400, False
            self.assertIsNone(monitor.inspect())
            clock[0] = 700
            self.assertIn("300", monitor.inspect())

    def test_saved_startup_anr_is_rejected_even_with_empty_lastanr_and_no_gradle_output(self):
        calls = []
        def adb(command, **kwargs):
            calls.append(command)
            if command[-1] == "devices":
                output = "emulator-5554\tdevice\n" if len(calls) > 1 else "List of devices attached\n"
            elif command[-1] == "ro.build.version.sdk":
                output = "36\n"
            elif command[-1] == "sys.boot_completed":
                output = "1\n"
            elif command[-3:] == ["emu", "avd", "name"]:
                output = "pixel2-managed-api36\nOK\n"
            elif "dropbox" in command:
                output = "system_app_anr\nProcess: com.android.systemui\nSubject: Process failed to complete startup\n"
            else:
                output = "ACTIVITY MANAGER LAST ANR\n  <no ANR has occurred since boot>\n"
            return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")
        with patch.object(ci, "adb_command", return_value="adb", create=True), \
                patch.object(ci.subprocess, "run", side_effect=adb):
            monitor = ci.DeviceHealth(36)
            self.assertIn("startup ANR", monitor.inspect())
        self.assertEqual(monitor.serial, "emulator-5554")
        self.assertEqual(monitor.avd, "pixel2-managed-api36")
        self.assertTrue(any("window" in c for c in calls))
        self.assertTrue(any("dropbox" in c for c in calls))

    def test_startup_anr_is_rejected_before_boot_completion_but_registering_services_are_pending(self):
        for response, expected in [("report", "startup ANR"), ("registering", None)]:
            with self.subTest(response=response):
                enumerations = 0
                def adb(command, **kwargs):
                    nonlocal enumerations
                    if command[-1] == "devices":
                        enumerations += 1
                        output = "emulator-5554\tdevice\n" if enumerations > 1 else ""
                    elif command[-1] == "ro.build.version.sdk":
                        output = "36\n"
                    elif command[-1] == "sys.boot_completed":
                        output = "0\n"
                    elif command[-3:] == ["emu", "avd", "name"]:
                        output = "managed-avd\nOK\n"
                    elif response == "registering":
                        return ci.subprocess.CompletedProcess(command, 1, stdout="", stderr="service not registered")
                    elif "dropbox" in command:
                        output = "Process: com.android.systemui\nSubject: Process failed to complete startup\n"
                    else:
                        output = ""
                    return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")
                with patch.object(ci, "adb_command", return_value="adb"), \
                        patch.object(ci.subprocess, "run", side_effect=adb):
                    reason = ci.DeviceHealth(36).inspect()
                if expected is None:
                    self.assertIsNone(reason)
                else:
                    self.assertIn(expected, reason)

    def test_device_admission_rejects_preexisting_ambiguous_and_wrong_api_targets(self):
        for devices, actual_api in [("emulator-5554\tdevice\n", "35"),
                                    ("emulator-5554\tdevice\nemulator-5556\tdevice\n", "36")]:
            with self.subTest(devices=devices), \
                    patch.object(ci, "adb_command", return_value="adb", create=True):
                replies = [ci.subprocess.CompletedProcess([], 0, stdout="", stderr=""),
                           ci.subprocess.CompletedProcess([], 0, stdout=devices, stderr=""),
                           ci.subprocess.CompletedProcess([], 0, stdout=actual_api, stderr="")]
                with patch.object(ci.subprocess, "run", side_effect=replies):
                    monitor = ci.DeviceHealth(36)
                    self.assertIsNotNone(monitor.inspect())
                    self.assertIsNone(monitor.serial, "Unverified devices must never become cleanup targets")
        with patch.object(ci, "adb_command", return_value="adb", create=True), \
                patch.object(ci.subprocess, "run", return_value=ci.subprocess.CompletedProcess(
                    [], 0, stdout="emulator-5554\tdevice\n", stderr="")):
            with self.assertRaisesRegex(ValueError, "preexisting"):
                ci.DeviceHealth(36)

    def test_silent_owned_command_has_deadline_cleanup_and_retains_partial_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            record = {}
            with patch.object(ci, "DeviceHealth", create=True) as health, \
                    patch.object(ci, "capture_system_ui_diagnostics", return_value={"path": "timeout-manifest"}):
                health.return_value.inspect.return_value = None
                started = time.monotonic()
                ci.run_gradle([sys.executable, "-u", "-c",
                               "import threading; print('partial output', flush=True); threading.Event().wait()"],
                              directory, "app", 36, record, timeout=1)
            self.assertLess(time.monotonic() - started, 10)
            self.assertEqual(record["abort_reason"], "execution deadline exceeded")
            self.assertTrue(record["process_cleanup_passed"])
            self.assertNotEqual(record["exit_code"], 0)
            self.assertIn("partial output", (directory / "app.log").read_text(encoding="utf-8"))

    def test_unhealthy_setup_is_captured_then_stopped_without_waiting_for_suite_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            record = {}
            with patch.object(ci, "DeviceHealth", create=True) as health, \
                    patch.object(ci, "capture_system_ui_diagnostics", return_value={"path": "saved-manifest"}) as capture:
                health.return_value.inspect.return_value = "SystemUI startup ANR"
                ci.run_gradle([sys.executable, "-u", "-c", "import threading; threading.Event().wait()"],
                              directory, "data", 36, record, timeout=5)
            self.assertIn("startup ANR", record["abort_reason"])
            self.assertTrue(record["process_cleanup_passed"])
            self.assertEqual(record["system_ui_diagnostics"], {"path": "saved-manifest"})
            capture.assert_called_once()

    def test_fresh_artifacts_are_required_after_prior_green_outputs_and_startup_reuses_app_paths(self):
        fixture = InstrumentationEvidenceTests()
        for module in ("data", "app", "startup"):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = root / "evidence"
                source, suite = fixture.write_inventory(root, module, 36)
                original = source.read_bytes()
                self.assertTrue(ci.collect_reports(root, root / "old", module, 36)["mandatory_inventory_passed"])
                ci.quarantine_previous_outputs(root, directory, module, 36)
                with self.assertRaisesRegex(ValueError, "mandatory"):
                    ci.collect_reports(root, directory, module, 36)
                self.assertEqual((directory / "previous" / module / source.relative_to(root)).read_bytes(), original)
                fixture.write_inventory(root, module, 36)
                result = ci.collect_reports(root, directory, module, 36)
                self.assertTrue(result["mandatory_inventory_passed"])
                self.assertEqual(result["total"], len(suite))

    def test_cleanup_kills_only_verified_owned_emulator_and_rejects_identity_changes(self):
        for changed in (False, True):
            with self.subTest(changed=changed):
                commands, enumerations, names = [], 0, 0
                def adb(command, **kwargs):
                    nonlocal enumerations, names
                    commands.append(command)
                    if command[-1] == "devices":
                        enumerations += 1
                        output = "emulator-5554\tdevice\n" if enumerations in (2, 3) else ""
                    elif command[-1] == "ro.build.version.sdk":
                        output = "36\n"
                    elif command[-1] == "sys.boot_completed":
                        output = "1\n"
                    elif command[-3:] == ["emu", "avd", "name"]:
                        names += 1
                        output = "foreign-avd\nOK\n" if changed and names == 2 else "managed-avd\nOK\n"
                    else:
                        output = ""
                    return ci.subprocess.CompletedProcess(command, 0, stdout=output, stderr="")
                with patch.object(ci, "adb_command", return_value="adb"), \
                        patch.object(ci.subprocess, "run", side_effect=adb):
                    monitor = ci.DeviceHealth(36)
                    self.assertIsNone(monitor.inspect())
                    self.assertEqual(monitor.cleanup(), not changed)
                kills = [c for c in commands if c[-2:] == ["emu", "kill"]]
                self.assertEqual(kills, [] if changed else [["adb", "-s", "emulator-5554", "emu", "kill"]])

    def test_linux_cleanup_adopts_detached_children_escalates_and_never_kills_baseline_processes(self):
        baseline = {11: (100, "old-start", "S")}
        current = dict(baseline)
        killed = []
        def kill(pid, sig):
            killed.append((pid, sig))
            if sig == 9:
                current.pop(pid)
        with patch.object(ci.sys, "platform", "linux"), \
                patch.object(ci.ctypes, "CDLL") as libc, \
                patch.object(ci, "linux_descendants", side_effect=lambda: dict(current)), \
                patch.object(ci.os, "kill", side_effect=kill, create=True), \
                patch.object(ci.signal, "SIGKILL", 9, create=True), \
                patch.object(ci.time, "monotonic", side_effect=[0, 0, 6]), \
                patch.object(ci.time, "sleep"):
            libc.return_value.prctl.return_value = 0
            owned = ci.OwnedProcesses()
            current[99] = (100, "detached-start", "S")
            self.assertTrue(owned.cleanup())
        self.assertEqual(killed, [(99, ci.signal.SIGTERM), (99, 9)])
        self.assertEqual(current, baseline)

    def test_linux_cleanup_reports_surviving_children_instead_of_silently_continuing(self):
        with patch.object(ci.sys, "platform", "linux"), \
                patch.object(ci.ctypes, "CDLL") as libc, \
                patch.object(ci, "linux_descendants", side_effect=[{}, {99: (100, "start", "S")}]), \
                patch.object(ci.time, "monotonic", side_effect=[0, 11]):
            libc.return_value.prctl.return_value = 0
            owned = ci.OwnedProcesses()
            self.assertFalse(owned.cleanup())


class HostMemoryProfileTests(unittest.TestCase):
    def test_swangle_requires_explicit_linux_api26_memory_experiment(self):
        for api, platform, profile, sensor in ((26, "linux", False, True), (26, "win32", True, True),
                                               (35, "linux", True, True), (26, "linux", True, False)):
            args = ["ci", "--api", str(api), "--api26-swangle"]
            args += ["--profile-host-memory"] if profile else []
            args += ["--trace-host-signals"] if sensor else []
            with self.subTest(args=args, platform=platform), patch("sys.argv", args), \
                    patch.object(ci.sys, "platform", platform), contextlib.redirect_stderr(io.StringIO()), \
                    patch.object(ci, "run_gradle") as worker:
                with self.assertRaises(SystemExit):
                    ci.main()
                worker.assert_not_called()

    def test_swangle_runs_all_suites_with_same_budget_inventory_and_failure_status(self):
        for app_exit in (0, 7):
            with self.subTest(app_exit=app_exit), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                commands = []
                def gradle(command, directory, module, api, record, timeout, profile_memory):
                    self.assertTrue(profile_memory)
                    self.assertEqual(api, 26)
                    self.assertEqual(timeout, 6600)
                    target = "app" if module == "startup" else module
                    expected = ["./gradlew", f":{target}:pixel2Api26DebugAndroidTest",
                                "-Plifetracing.compatibility.expectedApi=26",
                                "-Pandroid.testoptions.manageddevices.emulator.gpu=swangle",
                                "-Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5",
                                "--no-parallel", "--max-workers=2", "--no-daemon", "--no-configuration-cache",
                                "--rerun-tasks", "--no-build-cache", "--console=plain", "--stacktrace"]
                    if module == "startup":
                        expected.append("-Plifetracing.compatibility.productionStartup=true")
                    self.assertEqual(command, expected)
                    commands.append(module)
                    record.update(exit_code=app_exit if module == "app" else 0,
                                  process_cleanup_passed=True, device_cleanup_passed=True)
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                        patch.object(ci.sys, "platform", "linux"), patch.object(ci.time, "monotonic", return_value=10), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                        patch.object(ci.subprocess, "run"), patch.object(ci, "HostSignalObserver") as sensor, \
                        patch.object(ci, "emulator_revision", return_value={"revision": "37.2.13"}), \
                        patch.object(ci, "run_gradle", side_effect=gradle), \
                        patch.object(ci, "quarantine_previous_outputs"), \
                        patch.object(ci, "collect_reports", return_value=dict(total=1, observed_apis=[26],
                                                                             mandatory_inventory_passed=True)) as reports, \
                        patch("sys.argv", ["ci", "--api", "26", "--trace-host-signals", "--profile-host-memory",
                                           "--api26-swangle"]), contextlib.redirect_stdout(io.StringIO()):
                    sensor.return_value.start.return_value = {"calibrated": True}
                    sensor.return_value.close.return_value = True
                    self.assertEqual(ci.main(), int(app_exit != 0))
                    self.assertEqual(reports.call_count, 3)
                self.assertEqual(commands, ["data", "app", "startup"])
                evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
                self.assertEqual(evidence["requested_renderer"], "swangle")
                self.assertEqual(evidence["modules"]["app"]["status"], "FAILED" if app_exit else "PASSED")

    def test_memory_abort_cannot_certify_partial_suite_or_run_following_suites(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            def gradle(command, directory, module, api, record, timeout, profile_memory):
                self.assertTrue(profile_memory)
                record.update(exit_code=0, abort_reason="Host memory safety limit", process_cleanup_passed=True,
                              device_cleanup_passed=True)
            with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                    patch.object(ci.sys, "platform", "linux"), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                    patch.object(ci.subprocess, "run"), patch.object(ci, "HostSignalObserver") as sensor, \
                    patch.object(ci, "emulator_revision", return_value={"revision": "37.2.13"}), \
                    patch.object(ci, "run_gradle", side_effect=gradle) as worker, \
                    patch.object(ci, "quarantine_previous_outputs"), \
                    patch.object(ci, "collect_reports", return_value=dict(total=1, observed_apis=[26],
                                                                         mandatory_inventory_passed=True)), \
                    patch("sys.argv", ["ci", "--api", "26", "--trace-host-signals", "--profile-host-memory", "--api26-swangle"]), \
                    contextlib.redirect_stdout(io.StringIO()):
                sensor.return_value.start.return_value = {"calibrated": True}
                sensor.return_value.close.return_value = True
                self.assertEqual(ci.main(), 1)
                worker.assert_called_once()
            evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["data"]["status"], "FAILED")
            self.assertEqual(evidence["modules"]["app"]["status"], "NOT_RUN")
            self.assertEqual(evidence["modules"]["startup"]["status"], "NOT_RUN")

    def test_memory_experiment_requires_linux_api26_and_the_signal_sensor(self):
        for api, sensor, platform in ((35, True, "linux"), (26, False, "linux"), (26, True, "win32")):
            args = ["ci", "--api", str(api), "--profile-host-memory"] + (["--trace-host-signals"] if sensor else [])
            with patch("sys.argv", args), patch.object(ci.sys, "platform", platform), \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    ci.main()

    def test_emulator_revision_is_recorded_and_missing_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"ANDROID_HOME": temporary}):
            path = Path(temporary) / "emulator/source.properties"
            path.parent.mkdir()
            path.write_text("Pkg.Revision=37.2.13\n")
            self.assertEqual(ci.emulator_revision()["revision"], "37.2.13")
            path.write_text("Pkg.Desc=emulator\n")
            with self.assertRaisesRegex(ValueError, "revision"):
                ci.emulator_revision()

    def test_owned_emulator_memory_excludes_foreign_processes_and_pid_reuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary)
            (proc / "meminfo").write_text("MemTotal: 16000000 kB\nMemAvailable: 4000000 kB\n")
            for pid in (10, 11, 12):
                (proc / str(pid)).mkdir()
                (proc / str(pid) / "status").write_text(
                    "VmRSS: 5000000 kB\nVmHWM: 6000000 kB\nRssAnon: 4500000 kB\n")
            labels = {10: dict(pid=10, comm="qemu-system-x86", start="100"),
                      11: dict(pid=11, comm="qemu-system-x86", start="CHANGED"),
                      12: dict(pid=12, comm="java", start="102")}
            with patch.object(ci, "linux_descendants", return_value={
                    10: (1, "100", "S"), 11: (1, "101", "S"), 12: (1, "102", "S")}), \
                    patch.object(ci, "process_label", side_effect=labels.get):
                sample = ci.owned_memory_sample(proc)
            self.assertEqual([p["pid"] for p in sample["processes"]], [10, 12])
            self.assertEqual(sample["emulator_rss_kib"], 5000000)
            self.assertEqual(sample["host_kib"]["MemAvailable"], 4000000)

    def test_memory_limit_aborts_before_expensive_guest_capture_and_is_durable(self):
        for sample in (dict(emulator_rss_kib=8 * 1024 * 1024, host_kib={"MemAvailable": 4000000}),
                       dict(emulator_rss_kib=1000000, host_kib={"MemAvailable": 1024 * 1024})):
            with self.subTest(sample=sample), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(ci, "owned_memory_sample", return_value=sample), \
                    patch.object(ci, "guest_memory_sample") as guest, contextlib.redirect_stdout(io.StringIO()):
                profile = ci.HostMemoryProfile(Path(temporary), "app")
                self.assertIn("Host memory safety limit", profile.inspect(None))
                guest.assert_not_called()
                saved = json.loads((Path(temporary) / "app-host-memory.jsonl").read_text())
                self.assertEqual(saved["emulator_rss_kib"], sample["emulator_rss_kib"])
                self.assertEqual(saved["module"], "app")

    def test_memory_read_failure_is_explicit_not_zero_memory(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises((OSError, ValueError)):
                ci.owned_memory_sample(Path(temporary))

    def test_low_memory_command_is_stopped_and_cannot_be_success(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ci, "DeviceHealth") as health, \
                patch.object(ci, "HostMemoryProfile") as profile, \
                patch.object(ci, "capture_system_ui_diagnostics") as capture, \
                contextlib.redirect_stdout(io.StringIO()):
            health.return_value.inspect.return_value = None
            health.return_value.cleanup.return_value = True
            health.return_value.serial = health.return_value.avd = None
            health.return_value.observations = []
            profile.return_value.inspect.return_value = "Host memory safety limit: emulator RSS"
            record = {}
            ci.run_gradle([sys.executable, "-c", "import time; time.sleep(30)"],
                          Path(temporary), "app", 26, record, timeout=5, profile_memory=True)
            self.assertIn("Host memory safety limit", record["abort_reason"])
            self.assertNotEqual(record["exit_code"], 0)
            self.assertTrue(record["process_cleanup_passed"])
            capture.assert_not_called()


class HostSignalObserverTests(unittest.TestCase):
    def test_signal_record_identifies_sender_and_receiver_without_claiming_external_initiator(self):
        line = ("  Runner.Worker-1370 [007] d..1. 303.735156: signal_generate: "
                "sig=15 errno=0 code=0 comm=java pid=1376 grp=1 res=0")
        event = ci.signal_event(line)
        self.assertEqual((event["sender_comm"], event["sender_tid"]), ("Runner.Worker", 1370))
        self.assertEqual((event["target_comm"], event["target_pid"], event["signal"]), ("java", 1376, 15))
        self.assertEqual(event["trace_seconds"], "303.735156")
        self.assertNotIn("initiator", event)
        self.assertIsNone(ci.signal_event(line.replace("sig=15", "sig=18")))
        self.assertEqual(ci.signal_event(line.replace("sig=15", "sig=9"))["signal"], 9)
        self.assertIsNone(ci.signal_event("unrelated diagnostic message"))

    def test_process_label_reads_only_stat_and_preserves_start_identity(self):
        fields = ["S", "40"] + ["0"] * 17 + ["12345"]
        with patch.object(ci.Path, "read_text", return_value="42 (test (worker)) " + " ".join(fields)) as read:
            label = ci.process_label(42)
        self.assertEqual(label, dict(pid=42, comm="test (worker)", parent=40, start="12345", state="S"))
        read.assert_called_once_with()
        with patch.object(ci.Path, "read_text", side_effect=FileNotFoundError):
            self.assertIsNone(ci.process_label(42))

    def test_unavailable_observer_does_not_silently_run_unobserved_experiment(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ci.sys, "platform", "win32"):
            observer = ci.HostSignalObserver(Path(temporary))
            with self.assertRaisesRegex(RuntimeError, "Linux"):
                observer.start()
            self.assertIsNone(observer.process)

    def test_failed_observer_calibration_requests_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ci.sys, "platform", "linux"), \
                patch.object(ci.os, "geteuid", return_value=0, create=True), \
                patch.object(ci, "process_label", return_value=dict(start="1")), \
                patch.object(ci.subprocess, "Popen") as worker:
            worker.return_value.poll.return_value = 1
            worker.return_value.wait.return_value = 1
            observer = ci.HostSignalObserver(Path(temporary))
            with self.assertRaisesRegex(RuntimeError, "calibration"):
                observer.start()
            self.assertTrue((observer.directory / "stop").exists())
            self.assertTrue(worker.call_args.kwargs["start_new_session"])

    def test_observation_keeps_test_failure_and_clean_success_status(self):
        for exit_code, cleanup, expected in ((0, True, 0), (1, True, 1), (0, False, 1)):
            with self.subTest(exit_code=exit_code, cleanup=cleanup), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                commands = []
                def gradle(command, directory, module, api, record, timeout):
                    commands.append(command)
                    record.update(exit_code=exit_code, process_cleanup_passed=True, device_cleanup_passed=True)
                reports = dict(total=2, failures=0, errors=0, observed_apis=[26], mandatory_inventory_passed=True)
                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                        patch.object(ci.subprocess, "run"), patch.object(ci, "HostSignalObserver") as observer, \
                        patch.object(ci, "run_gradle", side_effect=gradle), \
                        patch.object(ci, "quarantine_previous_outputs"), \
                        patch.object(ci, "collect_reports", return_value=reports), \
                        patch("sys.argv", ["ci", "--api", "26", "--trace-host-signals"]), \
                        contextlib.redirect_stdout(io.StringIO()):
                    observer.return_value.start.return_value = {"calibrated": True}
                    observer.return_value.close.return_value = cleanup
                    self.assertEqual(ci.main(), expected)
                    observer.return_value.close.assert_called_once()
                self.assertEqual(len(commands), 3)
                self.assertTrue(all("--rerun-tasks" in command for command in commands))
                evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
                self.assertEqual(evidence["modules"]["data"]["exit_code"], exit_code)
                self.assertEqual(evidence["host_signal_observer"]["cleanup_passed"], cleanup)

    def test_other_apis_cannot_accidentally_enable_experiment(self):
        with patch("sys.argv", ["ci", "--api", "36", "--trace-host-signals"]), \
                contextlib.redirect_stderr(io.StringIO()), patch.object(ci, "HostSignalObserver") as observer:
            with self.assertRaises(SystemExit) as failure:
                ci.main()
            self.assertEqual(failure.exception.code, 2)
            observer.assert_not_called()


class CleanupOfflineControl(unittest.TestCase):
    def run_sequence(self, sequence, *, api='26', avd='managed-avd', admitted=True):
        commands, clock = [], [0.0]
        states = iter([''] + sequence)
        last = ['']
        def adb(command, **kwargs):
            commands.append(command[1:])
            if command[-1] == 'devices':
                last[0] = next(states, last[0])
                return ci.subprocess.CompletedProcess(command, 0, 'List of devices attached\n'+last[0], '')
            if 'offline' in last[0]:
                return ci.subprocess.CompletedProcess(command, 1, '', 'adb: device offline\n')
            if command[-1] == 'ro.build.version.sdk':
                return ci.subprocess.CompletedProcess(command, 0, api+'\n', '')
            if command[-3:] == ['emu','avd','name']:
                return ci.subprocess.CompletedProcess(command, 0, avd+'\nOK\n', '')
            if command[-2:] == ['emu','kill']:
                return ci.subprocess.CompletedProcess(command, 0, 'OK\n', '')
            raise AssertionError(command)
        with patch.object(ci, 'adb_command', return_value='adb'), patch.object(ci.subprocess, 'run', side_effect=adb), \
                patch.object(ci.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(ci.time, 'sleep', side_effect=lambda amount: clock.__setitem__(0, clock[0]+amount)):
            monitor = ci.DeviceHealth(26)
            if admitted:
                monitor.serial, monitor.avd = 'emulator-5554', 'managed-avd'
            result = monitor.cleanup()
        return result, commands, clock[0]

    def test_stopped_serial_must_actually_unregister_without_guest_command(self):
        result, commands, elapsed = self.run_sequence(['emulator-5554\toffline\n','emulator-5554\toffline\n',''])
        self.assertTrue(result)
        self.assertTrue(all(c==['devices'] for c in commands))
        self.assertGreater(elapsed, 0)

    def test_persistent_offline_is_failure_within_existing_wait_budget(self):
        result, commands, elapsed = self.run_sequence(['emulator-5554\toffline\n'])
        self.assertFalse(result)
        self.assertLessEqual(elapsed, 10.21)
        self.assertTrue(all(c==['devices'] for c in commands))

    def test_foreign_registration_during_offline_wait_is_rejected(self):
        for foreign in ['emulator-5556\tdevice\n', 'emulator-5554\toffline\nemulator-5556\tdevice\n']:
            with self.subTest(foreign=foreign):
                result, commands, _ = self.run_sequence(['emulator-5554\toffline\n',foreign])
                self.assertFalse(result)
                self.assertTrue(all(c==['devices'] for c in commands))

    def test_offline_to_online_does_not_get_unvalidated_kill_or_success(self):
        result, commands, _ = self.run_sequence(['emulator-5554\toffline\n','emulator-5554\tdevice\n'])
        self.assertFalse(result)
        self.assertTrue(all(c==['devices'] for c in commands))

    def test_never_admitted_offline_device_is_rejected(self):
        result, commands, _ = self.run_sequence(['emulator-5554\toffline\n'],admitted=False)
        self.assertFalse(result)
        self.assertTrue(all(c==['devices'] for c in commands))

    def test_online_identity_changes_still_block_kill(self):
        for api,avd in [('36','managed-avd'),('26','foreign-avd')]:
            result, commands, _ = self.run_sequence(['emulator-5554\tdevice\n'],api=api,avd=avd)
            self.assertFalse(result)
            self.assertFalse(any(c[-2:]==['emu','kill'] for c in commands))

    def test_online_exact_identity_can_be_killed_and_requires_absence(self):
        result, commands, _ = self.run_sequence(['emulator-5554\tdevice\n',''])
        self.assertTrue(result)
        self.assertEqual([c for c in commands if c[-2:]==['emu','kill']], [['-s','emulator-5554','emu','kill']])

    def test_adb_io_failure_remains_observable(self):
        with patch.object(ci,'adb_command',return_value='adb'), patch.object(ci.DeviceHealth,'devices',return_value=[]):
            monitor=ci.DeviceHealth(26)
            monitor.serial,monitor.avd='emulator-5554','managed-avd'
        with patch.object(ci.DeviceHealth,'devices',side_effect=OSError(61,'No data available')):
            with self.assertRaises(OSError):
                monitor.cleanup()


class WorkflowCachePolicyTests(unittest.TestCase):
    def cache_exclusions_by_api(self):
        import re
        workflow = Path(__file__).parents[1] / "workflows" / "ci.yml"
        row = workflow.read_text().split("  instrumented-tests:", 1)[1]
        matrix = row.split("        include:", 1)[1].split("    runs-on:", 1)[0]
        result = {}
        for api, fields in re.findall(r"- api: (\d+)\n(.*?)(?=          - api:|\Z)", matrix, re.S):
            match = re.search(r"^            gradle_cache_excludes: (.+)$", fields, re.M)
            result[int(api)] = match.group(1).strip() if match else None
        return result

    def test_transform_cache_exclusion_applies_only_to_api36(self):
        rows = self.cache_exclusions_by_api()
        self.assertEqual(set(rows), {26, 31, 33, 35, 36})
        self.assertEqual({api for api, value in rows.items() if value}, {36})

    def test_exclusion_covers_current_gradle_transforms_without_other_cache_content(self):
        import re
        repository = Path(__file__).parents[2]
        wrapper = (repository / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text()
        version = re.search(r"gradle-([0-9.]+)-(?:bin|all)\.zip", wrapper).group(1)
        excluded = self.cache_exclusions_by_api()[36]
        self.assertEqual(excluded, f"caches/{version}/transforms")
        self.assertNotIn("..", Path(excluded).parts)
        for retained in ["caches/modules-2", "wrapper/dists", f"caches/{version}/kotlin-dsl", "caches/build-cache-1"]:
            self.assertFalse(retained == excluded or retained.startswith(excluded + "/"))


if __name__ == "__main__":
    unittest.main()
