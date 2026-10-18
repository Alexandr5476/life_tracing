import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("instrumentation", Path(__file__).with_name("run-instrumentation.py"))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class InstrumentationEvidenceTests(unittest.TestCase):
    def write_inventory(self, root, module="data", api=26):
        inventory = json.loads(Path(__file__).with_name("instrumentation-inventory.json").read_text())
        suite = ET.Element("testsuite")
        for classname, names in inventory[module].items():
            for name in names:
                ET.SubElement(suite, "testcase", classname=classname, name=name.format(api=api))
        source = root / module / f"build/outputs/androidTest-results/managedDevice/debug/pixel2Api{api}/TEST-suite.xml"
        source.parent.mkdir(parents=True)
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
        for module in ("data", "app"):
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
        for module in ("data", "app"):
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
            env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                       GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
            with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                    patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                    patch.object(ci, "sdkmanager_command", return_value=["sdkmanager"]), \
                    patch.object(ci.subprocess, "run"), patch.object(ci.subprocess, "Popen") as worker, \
                    patch("sys.argv", ["ci", "--api", "26"]), contextlib.redirect_stdout(io.StringIO()):
                worker.return_value.__enter__.return_value.stdout = []
                worker.return_value.__enter__.return_value.wait.return_value = 0
                self.assertEqual(ci.main(), 1)
            evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["data"]["exit_code"], 0)
            self.assertIn("mandatory", evidence["modules"]["data"]["evidence_error"])
            self.assertTrue(evidence["modules"]["app"]["mandatory_inventory_passed"])
            self.assertEqual((root / "ci-evidence/api26" / source.relative_to(root)).read_bytes(), source.read_bytes())
            self.assertEqual(worker.call_count, 2)

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
                self.stdout = iter(["> Task " + command[1] + " FAILED\n"])
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
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
            install.assert_called_once_with(["sdkmanager", "--install", "emulator"], check=True)
            self.assertEqual([c[1] for c in commands], [":data:pixel2Api26DebugAndroidTest", ":app:pixel2Api26DebugAndroidTest"])
            evidence = json.loads((Path(temporary) / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["app"]["exit_code"], 1)
            self.assertNotEqual(evidence["event_sha"], evidence["tested_sha"])

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
                    worker.return_value.__enter__.return_value.stdout = []
                    worker.return_value.__enter__.return_value.wait.return_value = 0
                    self.assertEqual(ci.main(), 0)
                self.assertEqual(calls, ([("install", ["sdkmanager", "--install", "emulator"])]
                                         if api == 26 else []) + [("suite", "data"), ("suite", "app")])
                commands = [call.args[0] for call in worker.call_args_list]
                self.assertEqual([c[1] for c in commands],
                                 [f":data:pixel2Api{api}DebugAndroidTest", f":app:pixel2Api{api}DebugAndroidTest"])
                self.assertTrue(all(not any("Arguments.class" in arg for arg in c) for c in commands))

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
                    self.assertEqual(kwargs, {"check": True})
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
                    worker.return_value.__enter__.return_value.stdout = []
                    worker.return_value.__enter__.return_value.wait.return_value = 0
                    self.assertEqual(ci.main(), 0)
                installation.assert_called_once_with(command, check=True)
                self.assertEqual([call.args[0][1] for call in worker.call_args_list],
                                 [":data:pixel2Api26DebugAndroidTest", ":app:pixel2Api26DebugAndroidTest"])
                evidence = json.loads((root / "ci-evidence/api26/evidence.json").read_text())
                self.assertEqual(set(evidence["modules"]), {"data", "app"})
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

    def test_merge_checkout_is_rejected_before_running_gradle(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(ci.Path, "cwd", return_value=Path(temporary)), \
                    patch.dict(os.environ, {"EXPECTED_TEST_SHA": "a" * 40}), \
                    patch.object(ci.subprocess, "check_output", return_value="b" * 40), \
                    patch.object(ci.subprocess, "Popen") as worker, patch("sys.argv", ["ci", "--api", "26"]):
                with self.assertRaisesRegex(RuntimeError, "Checkout mismatch"):
                    ci.main()
                worker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
