import importlib.util
from pathlib import Path
import tempfile
import unittest
import contextlib
import io
import json
import os
from unittest.mock import patch, Mock


spec = importlib.util.spec_from_file_location('profile', Path(__file__).with_name('api26_profile.py'))
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)
runner_spec = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('run-instrumentation.py'))
runner = importlib.util.module_from_spec(runner_spec)
runner_spec.loader.exec_module(runner)


class ProfileAdmissionTests(unittest.TestCase):
    def test_installed_probe_artifact_survives_cleanup_without_private_signing_key(self):
        import hashlib
        import shutil
        import zipfile
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root/'sdk'
            for relative in ['build-tools/35.0.0/aapt2', 'build-tools/35.0.0/d8',
                             'build-tools/35.0.0/apksigner', 'platforms/android-36/android.jar']:
                path = sdk/relative; path.parent.mkdir(parents=True, exist_ok=True); path.touch()
            evidence = root/'evidence'; evidence.mkdir()
            helpers = Mock(); helpers.timestamp.return_value = 'now'
            helpers.OwnedProcesses.return_value.cleanup.return_value = True
            with patch.object(profile.Path, 'cwd', return_value=root), \
                    patch.dict(os.environ, {'ANDROID_HOME':str(sdk), 'ANDROID_ADB_SERVER_PORT':'5037',
                                           'ADB_SERVER_SOCKET':'tcp:5037'}):
                value = profile.Api26Profile(helpers, evidence, 100)
                def command(arguments, label, *_):
                    if label == 'probe-package':
                        with zipfile.ZipFile(arguments[-1], 'w') as archive:
                            archive.writestr('AndroidManifest.xml', b'manifest')
                    elif label == 'probe-dex':
                        (Path(arguments[arguments.index('--output')+1])/'classes.dex').write_bytes(b'dex')
                    elif label == 'probe-key':
                        Path(arguments[arguments.index('-keystore')+1]).write_bytes(b'private diagnostic key')
                    elif label == 'probe-sign':
                        shutil.copyfile(arguments[-1], arguments[arguments.index('--out')+1])
                    return ''
                value.command = command
                apk = value.build_probe(); expected = apk.read_bytes()
                value.close()
                self.assertTrue(apk.is_file(), 'The exact APK used by both installs must survive cleanup')
                self.assertEqual(apk.read_bytes(), expected)
                self.assertEqual(value.record['probe']['apk_sha256'], hashlib.sha256(expected).hexdigest())
                self.assertEqual(value.record['artifacts'][apk.relative_to(value.directory).as_posix()],
                                 hashlib.sha256(expected).hexdigest())
                self.assertFalse(value.temporary.exists())
                self.assertEqual(list(evidence.rglob('*.jks')), [])

    def test_scaled_raster_requires_actual_display_readback_and_preserves_logical_viewport(self):
        good = ('INSTRUMENTATION_RESULT: actual_api=26\n'
                'INSTRUMENTATION_RESULT: snapshot_actual=false\n'
                'INSTRUMENTATION_RESULT: snapshot_property=false\n'
                'INSTRUMENTATION_RESULT: display_width_px=720\n'
                'INSTRUMENTATION_RESULT: display_height_px=1280\n'
                'INSTRUMENTATION_RESULT: display_density_dpi=280\n'
                'INSTRUMENTATION_RESULT: app_width_px=720\n'
                'INSTRUMENTATION_RESULT: app_height_px=1196\nINSTRUMENTATION_CODE: 0\n')
        result = profile.verify_probe(good, scaled_raster=True)
        self.assertEqual(result['display'], dict(width_px=720, height_px=1280, density_dpi=280,
                                               app_width_px=720, app_height_px=1196))
        for field, wrong in [('display_width_px', '1080'), ('display_height_px', '1920'),
                             ('display_density_dpi', '420'), ('app_width_px', '719'), ('app_height_px', '1280')]:
            import re
            for bad in [re.sub(field+'=\\d+', field+'='+wrong, good),
                        re.sub('INSTRUMENTATION_RESULT: '+field+'=\\d+\\n', '', good),
                        good+'INSTRUMENTATION_RESULT: '+field+'='+wrong+'\n']:
                with self.subTest(field=field), self.assertRaises(ValueError):
                    profile.verify_probe(bad, scaled_raster=True)

    def test_scaled_raster_requires_both_owned_guest_overrides(self):
        value = profile.Api26Profile.__new__(profile.Api26Profile)
        value.scaled_raster = True
        value.shell = Mock(side_effect=['', '', 'Physical size: 1080x1920\nOverride size: 720x1280\n',
                                       'Physical density: 420\nOverride density: 280\n'])
        value.configure_raster()
        self.assertEqual(value.shell.call_args_list[0].args[0], ['wm', 'size', '720x1280'])
        self.assertEqual(value.shell.call_args_list[1].args[0], ['wm', 'density', '280'])
        value.shell = Mock(side_effect=['', '', 'Physical size: 1080x1920\n', 'Physical density: 420\n'])
        with self.assertRaises(ValueError): value.configure_raster()
        value.scaled_raster = False; value.shell.reset_mock()
        value.configure_raster(); value.shell.assert_not_called()

    def test_scaled_raster_cannot_bypass_snapshot_preparation(self):
        with patch('sys.argv', ['ci', '--api', '26', '--trace-host-signals', '--profile-host-memory',
                               '--api26-swangle', '--api26-scaled-raster']), \
                patch.object(runner, 'create_api26_profile') as factory, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit): runner.main()
            factory.assert_not_called()

    def test_restore_uses_a_distinct_port_pair_and_never_launches_on_an_occupied_pair(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=profile.Api26Profile.__new__(profile.Api26Profile)
            value.directory=Path(temporary);value.avd=Path(temporary)/'device.avd'
            value.sdk=Path('sdk');value.name='device';value.ci=Mock();value.boot=Mock();value.record={}
            with patch.object(profile.socket,'socket') as socket,patch.object(profile.subprocess,'Popen') as launch:
                for restore in [False,True]:
                    value.start_emulator(restore);value.emulator_log.close()
                commands=[c.args[0] for c in launch.call_args_list]
                self.assertEqual([c[c.index('-port')+1] for c in commands],['5580','5582'])
                self.assertEqual(value.serial,'emulator-5582')
                launch.reset_mock()
                socket.return_value.__enter__.return_value.bind.side_effect=OSError(98,'Address already in use')
                with self.assertRaises((OSError,ValueError)):value.start_emulator(True)
                launch.assert_not_called()

    def test_owned_emulator_exit_waits_for_adb_unregistration_without_accepting_foreign_device(self):
        value=profile.Api26Profile.__new__(profile.Api26Profile)
        value.adb='adb';value.serial='emulator-5580';value.deadline=100
        value.emulator=Mock();value.emulator_log=Mock();value.ci=Mock()
        value.command=Mock(side_effect=['OK','List of devices attached\nemulator-5580 offline\n','List of devices attached\n'])
        with patch.object(profile.time,'monotonic',return_value=0),patch.object(profile.time,'sleep'):
            value.stop_emulator()
        self.assertEqual(value.command.call_count,3)
        value.emulator_log=Mock()
        value.command=Mock(side_effect=['OK','List of devices attached\nemulator-5554 device\n'])
        with patch.object(profile.time,'monotonic',return_value=0),self.assertRaises(ValueError):value.stop_emulator()

    def test_large_avd_uses_owned_workspace_storage_instead_of_default_tmpfs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            evidence=root/'evidence';evidence.mkdir()
            helpers=Mock()
            helpers.timestamp.return_value='now'
            helpers.OwnedProcesses.return_value.cleanup.return_value=True
            with patch.object(profile.Path,'cwd',return_value=root), \
                    patch.dict(os.environ,{'ANDROID_HOME':'sdk','ANDROID_ADB_SERVER_PORT':'5037','ADB_SERVER_SOCKET':'tcp:5037'}):
                value=profile.Api26Profile(helpers,evidence,100)
                try:self.assertEqual(value.temporary.parent,root/'.gradle')
                finally:value.close()

    def test_profile_rejects_alternate_adb_transport_before_acquiring_resources(self):
        with patch.dict(os.environ, {'ANDROID_ADB_SERVER_PORT':'5047'}), \
                patch.object(profile.tempfile, 'mkdtemp') as temporary:
            with self.assertRaisesRegex(ValueError, '5037'):
                profile.Api26Profile(None, Path('unused'), 100)
            temporary.assert_not_called()

    def test_profile_cannot_be_enabled_without_guarded_api26_swangle(self):
        for args in (['--api','26'], ['--api','35','--api26-swangle','--profile-host-memory','--trace-host-signals']):
            with patch('sys.argv', ['ci',*args,'--api26-disable-task-snapshots']), \
                    patch.object(runner, 'create_api26_profile') as factory, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit): runner.main()
                factory.assert_not_called()

    def test_profile_failure_blocks_every_suite_and_preserves_shared_budget(self):
        for defect, scaled in [(d, s) for d in ('prepare', 'cleanup', None) for s in (False, True)]:
            with self.subTest(defect=defect, scaled=scaled), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                env = dict(EXPECTED_TEST_SHA='a'*40, GITHUB_SHA='b'*40, GITHUB_RUN_ID='7',
                           GITHUB_RUN_ATTEMPT='1', GITHUB_EVENT_NAME='pull_request')
                commands = []
                def gradle(command, directory, module, api, record, timeout, profile_memory):
                    commands.append((module, command))
                    self.assertLessEqual(timeout, 6600)
                    self.assertTrue(profile_memory)
                    self.assertIn('-Plifetracing.compatibility.taskSnapshotsDisabled=true', command)
                    self.assertEqual('-Plifetracing.compatibility.scaledRaster=true' in command, scaled)
                    record.update(exit_code=0, process_cleanup_passed=True, device_cleanup_passed=True)
                # Orchestration is synthetic: use an exact clock, not host uptime rounding.
                with patch.object(runner.Path, 'cwd', return_value=root), patch.dict(os.environ, env), \
                        patch.object(runner.time, 'monotonic', return_value=0), \
                        patch.object(runner.sys, 'platform', 'linux'), \
                        patch.object(runner.subprocess, 'check_output', return_value='a'*40), \
                        patch.object(runner.subprocess, 'run') as install, \
                        patch.object(runner, 'sdkmanager_command', return_value=['sdkmanager','--install','emulator']), \
                        patch.object(runner, 'HostSignalObserver') as sensor, \
                        patch.object(runner, 'emulator_revision', return_value={'revision':'37.2.13'}), \
                        patch.object(runner, 'create_api26_profile', create=True) as factory, \
                        patch.object(runner, 'run_gradle', side_effect=gradle), \
                        patch.object(runner, 'quarantine_previous_outputs'), \
                        patch.object(runner, 'collect_reports', return_value=dict(total=1, observed_apis=[26], mandatory_inventory_passed=True)) as reports, \
                        patch('sys.argv', ['ci','--api','26','--trace-host-signals','--profile-host-memory',
                                           '--api26-swangle','--api26-disable-task-snapshots']
                                           + (['--api26-scaled-raster'] if scaled else [])), \
                        contextlib.redirect_stdout(io.StringIO()):
                    sensor.return_value.start.return_value = {'calibrated':True}
                    sensor.return_value.close.return_value = True
                    factory.return_value.record = {'status':'FAILED' if defect=='prepare' else 'PREPARED'}
                    factory.return_value.close.return_value = defect != 'cleanup'
                    if defect == 'prepare': factory.return_value.prepare.side_effect = ValueError('field true')
                    self.assertEqual(runner.main(), int(defect is not None))
                    self.assertIn('build-tools;35.0.0', install.call_args.args[0])
                    self.assertIn('platforms;android-36', install.call_args.args[0])
                    factory.return_value.close.assert_called_once()
                    self.assertEqual(reports.call_count, 0 if defect=='prepare' else 3)
                evidence = json.loads((root/'ci-evidence/api26/evidence.json').read_text())
                if defect == 'prepare':
                    self.assertEqual(commands, [])
                    self.assertTrue(all(v['status']=='NOT_RUN' for v in evidence['modules'].values()))
                else: self.assertEqual([v[0] for v in commands], ['data','app','startup'])

    def test_requested_property_or_wrong_api_cannot_certify_framework_state(self):
        good = ('INSTRUMENTATION_RESULT: actual_api=26\n'
                'INSTRUMENTATION_RESULT: snapshot_actual=false\n'
                'INSTRUMENTATION_RESULT: snapshot_property=false\nINSTRUMENTATION_CODE: 0\n')
        self.assertEqual(profile.verify_probe(good)['actual_api'], 26)
        for bad in (good.replace('snapshot_actual=false', 'snapshot_actual=true'),
                    good.replace('actual_api=26', 'actual_api=35'),
                    good.replace('INSTRUMENTATION_CODE: 0', 'INSTRUMENTATION_CODE: 1'),
                    good.replace('snapshot_property=false', 'snapshot_property='),
                    good + 'INSTRUMENTATION_RESULT: probe_error=reflection failed\n'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                profile.verify_probe(bad)

    def test_loadability_exit_alone_cannot_certify_snapshot_restoration(self):
        profile.verify_restore("Successfully loaded snapshot 'default_boot' using 100 ms")
        for value in ('', 'Cold boot', "Could not load snapshot 'default_boot'", 'Snapshot is loadable'):
            with self.assertRaises(ValueError):
                profile.verify_restore(value)

    def test_avd_discovery_requires_one_actual_owned_api26_configuration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            avd = root/'gradle-managed/dev26_default_x86_64_Pixel_2.avd'
            avd.mkdir(parents=True)
            config = 'image.sysdir.1 = system-images/android-26/default/x86_64/\nhw.lcd.width = 1080\nhw.lcd.height = 1920\nhw.lcd.density = 420\nhw.cpu.ncore = 2\nhw.ramSize = 2G\n'
            (avd/'config.ini').write_text(config)
            self.assertEqual(profile.find_avd(root), avd)
            (avd/'config.ini').write_text(config.replace('android-26', 'android-35'))
            with self.assertRaises(ValueError): profile.find_avd(root)
            (avd/'config.ini').write_text(config)
            (avd.parent/'other.avd').mkdir()
            with self.assertRaises(ValueError): profile.find_avd(root)


if __name__ == '__main__': unittest.main()
