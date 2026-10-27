import importlib.util
import contextlib
import hashlib
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
                worker.return_value.__enter__.return_value.stdout = []
                worker.return_value.__enter__.return_value.wait.return_value = 0
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
                        self.stdout = self.output()

                    def output(self):
                        yield f"{self.module} command output\n"
                        if self.module == "app" and phase == "stream":
                            raise OSError("app stream interrupted")

                    def __enter__(self):
                        return self

                    def __exit__(self, *args):
                        if self.module == "app" and phase == "close":
                            raise OSError("app close interrupted")

                    def wait(self):
                        if self.module == "app" and phase == "wait":
                            raise OSError("app wait interrupted")
                        return 0

                env = dict(EXPECTED_TEST_SHA="a" * 40, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="7",
                           GITHUB_RUN_ATTEMPT="1", GITHUB_EVENT_NAME="pull_request")
                with patch.object(ci.Path, "cwd", return_value=root), patch.dict(os.environ, env), \
                        patch.object(ci.subprocess, "check_output", return_value="a" * 40), \
                        patch.object(ci.subprocess, "Popen", Process), \
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
                    worker.return_value.__enter__.return_value.stdout = []
                    worker.return_value.__enter__.return_value.wait.return_value = 0
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
                worker.return_value.__enter__.return_value.stdout = []
                worker.return_value.__enter__.return_value.wait.return_value = 0
                self.assertEqual(ci.main(), 1)
            evidence = json.loads((Path(temporary) / "ci-evidence/api35/evidence.json").read_text())
            self.assertTrue(evidence["modules"]["data"]["mandatory_inventory_passed"])
            self.assertTrue(evidence["modules"]["app"]["mandatory_inventory_passed"])
            self.assertFalse(evidence["modules"]["startup"]["mandatory_inventory_passed"])

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

    def test_system_ui_anr_captures_once_before_startup_and_cannot_pass_green_reports(self):
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
                    self.stdout = (["mCurrentFocus=Window{abc Application Not Responding: com.android.systemui}\n"] * 2
                                   if module == "app" else [])

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    pass

                def wait(self):
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
                    patch.object(ci.subprocess, "run", side_effect=capture) as captured, \
                    patch("sys.argv", ["ci", "--api", "36"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ci.main(), 1)
            app = json.loads((directory / "evidence.json").read_text(encoding="utf-8"))["modules"]["app"]
            self.assertTrue(app["system_ui_anr_observed"])
            self.assertTrue(app["mandatory_inventory_passed"])
            self.assertEqual(app["failures"], 0)
            self.assertEqual(captured.call_count, 7)
            diagnostics = json.loads((directory / "app-system-ui/manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(diagnostics["serial"], "emulator-5554")
            for record in diagnostics["commands"]:
                content = (directory / record["artifact"]["path"]).read_bytes()
                self.assertEqual(record["artifact"]["sha256"], hashlib.sha256(content).hexdigest())
            self.assertTrue(any("lastanr" in c.args[0] for c in captured.call_args_list))
            self.assertTrue(any("system_app_anr" in c.args[0] for c in captured.call_args_list))
            self.assertTrue(any("system" in c.args[0] and "events" in c.args[0] for c in captured.call_args_list))

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
            with self.subTest(devices=devices, api=actual_api), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                replies = [ci.subprocess.CompletedProcess([], 0, stdout=devices, stderr="")]
                if actual_api:
                    replies.append(ci.subprocess.CompletedProcess([], 0, stdout=actual_api, stderr=""))
                with patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}), \
                        patch.object(ci.shutil, "which", return_value="test-adb"), \
                        patch.object(ci.subprocess, "run", side_effect=replies) as captured:
                    ci.capture_system_ui_diagnostics(directory, "app", 36)
                manifest = json.loads((directory / "app-system-ui/manifest.json").read_text(encoding="utf-8"))
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


if __name__ == "__main__":
    unittest.main()
