import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("instrumentation", Path(__file__).with_name("run-instrumentation.py"))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class InstrumentationEvidenceTests(unittest.TestCase):
    def test_original_report_and_observed_api_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "data/build/outputs/androidTest-results/managedDevice/debug/pixel2Api26/TEST-suite.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<testsuite tests="1"><testcase classname="pkg.AndroidCompatibilityDeviceTest" '
                              'name="recordsActualApiAndRejectsMismatchedRequestedTarget[actualApi=26]"/></testsuite>')
            result = ci.collect_reports(root, root / "evidence", "data", 26)
            self.assertEqual(result["observed_apis"], [26])
            self.assertEqual(result["total"], 1)
            self.assertEqual((root / "evidence" / source.relative_to(root)).read_bytes(), source.read_bytes())
            source.write_text(source.read_text().replace('tests="1"', 'tests="2"'))
            with self.assertRaisesRegex(ValueError, "inconsistent JUnit"):
                ci.collect_reports(root, root / "evidence", "data", 26)

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
                    patch.object(ci.subprocess, "Popen", Process), patch("sys.argv", ["ci", "--api", "26"]):
                self.assertEqual(ci.main(), 1)
            self.assertEqual([c[1] for c in commands], [":data:pixel2Api26DebugAndroidTest", ":app:pixel2Api26DebugAndroidTest"])
            evidence = json.loads((Path(temporary) / "ci-evidence/api26/evidence.json").read_text())
            self.assertEqual(evidence["modules"]["app"]["exit_code"], 1)
            self.assertNotEqual(evidence["event_sha"], evidence["tested_sha"])

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
