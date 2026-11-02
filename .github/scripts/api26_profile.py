"""Prepare an owned API26 GMD boot snapshot before any LifeTracing test starts.

This module never changes a system image, allocator policy or a running suite.
It uses the tested Android 8 property plus a pre-test framework restart because
ActivityManager initializes ENABLE_TASK_SNAPSHOTS once in the zygote lifetime.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import zipfile


PACKAGE = 'org.lifetracing.ciprofile'
PROBE = PACKAGE + '/org.lifetracing.ciprofile.ProfileProbe'


class CommandTimeout(ValueError):
    pass


def verify_probe(output, scaled_raster=False):
    fields = dict(re.findall(r'^INSTRUMENTATION_RESULT: (\w+)=(.*)$', output, re.MULTILINE))
    if (fields.get('actual_api') != '26' or fields.get('snapshot_actual') != 'false'
            or fields.get('snapshot_property') != 'false' or 'probe_error' in fields
            or re.findall(r'^INSTRUMENTATION_CODE: (.*)$', output, re.MULTILINE) != ['0']):
        raise ValueError('Actual API26 framework task-snapshot profile was not confirmed')
    result = dict(actual_api=26, actual_field=False, actual_property=False)
    if scaled_raster:
        expected = dict(display_width_px='720', display_height_px='1280', display_density_dpi='280',
                        app_width_px='720', app_height_px='1196')
        for key, value in expected.items():
            if re.findall(r'^INSTRUMENTATION_RESULT: '+key+r'=(.*)$', output, re.MULTILINE) != [value]:
                raise ValueError('Actual API26 scaled display was not confirmed: '+key)
        result['display'] = dict(width_px=720, height_px=1280, density_dpi=280,
                                 app_width_px=720, app_height_px=1196)
    return result


def verify_restore(output):
    if not re.search(r"Successfully loaded snapshot 'default_boot' using \d+ ms", output):
        raise ValueError('Prepared default_boot was not actually restored')


def find_avd(root):
    managed = root / 'gradle-managed'
    candidates = list(managed.glob('*.avd'))
    if len(candidates) != 1:
        raise ValueError('Expected exactly one GMD-created AVD in the private profile directory')
    avd = candidates[0]
    if avd.is_symlink() or avd.resolve().parent != managed.resolve():
        raise ValueError('GMD AVD escaped the owned directory')
    values = dict(tuple(part.strip() for part in line.split('=', 1))
                  for line in (avd / 'config.ini').read_text().splitlines() if '=' in line)
    image = values.get('image.sysdir.1', '').replace('\\', '/')
    if (not image.rstrip('/').endswith('system-images/android-26/default/x86_64')
            or any(values.get(k) != v for k, v in {'hw.lcd.width': '1080', 'hw.lcd.height': '1920',
                                                 'hw.lcd.density': '420', 'hw.cpu.ncore': '2'}.items())
            or values.get('hw.ramSize') not in ('2G', '2048')):
        raise ValueError('Actual GMD AVD does not match the measured API26 Pixel2 profile')
    return avd


class Api26Profile:
    def __init__(self, ci, directory, row_deadline, scaled_raster=False):
        # Validate before acquiring temporary resources or changing child environment.
        if (os.environ.get('ANDROID_ADB_SERVER_PORT', '5037') != '5037'
                or os.environ.get('ADB_SERVER_SOCKET') not in (None, 'tcp:5037', 'tcp:localhost:5037')):
            raise ValueError('API26 profile requires the same default ADB5037 transport as UTP')
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if not sdk: raise ValueError('API26 profile requires an explicit SDK root')
        self.sdk = Path(sdk)
        self.ci, self.deadline = ci, row_deadline
        self.scaled_raster = scaled_raster
        self.directory = directory / 'task-snapshot-profile'
        self.directory.mkdir()
        self.record = dict(status='PREPARING', started_at=ci.timestamp(), steps=[],
                           requested_scaled_raster=scaled_raster)
        self.owned = ci.OwnedProcesses()
        # An AVD/snapshot is multi-GiB. /tmp can be a small RAM filesystem, which
        # also consumes host memory outside the emulator RSS guard.
        storage = Path.cwd() / '.gradle'
        storage.mkdir(exist_ok=True)
        self.record['storage_free_bytes'] = shutil.disk_usage(storage).free
        self.temporary = Path(tempfile.mkdtemp(prefix='lifetracing-api26-profile-', dir=storage)).resolve()
        self.old_avd_home = os.environ.get('ANDROID_AVD_HOME')
        self.avd_home = self.temporary / 'avd'
        self.avd_home.mkdir()
        self.memory = ci.HostMemoryProfile(self.directory, 'prepare')
        self.next_memory = 0
        self.emulator = None
        self.emulator_log = None
        self.sequence = 0
        self.adb = str(self.sdk / 'platform-tools/adb')
        self.serial = 'emulator-5580'
        # AGP's UTP controller explicitly uses5037, even when adb inherits another socket.
        os.environ['ANDROID_AVD_HOME'] = str(self.avd_home)

    def save(self):
        self.record['updated_at'] = self.ci.timestamp()
        (self.directory / 'profile.json').write_text(json.dumps(self.record, indent=2)+'\n', encoding='utf-8')

    def check(self):
        if time.monotonic() >= self.deadline:
            raise ValueError('API26 preparation exhausted the shared row deadline')
        if self.emulator_log is not None and self.emulator_path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError('Profile emulator output exceeded2MiB')
        if time.monotonic() >= self.next_memory:
            reason = self.memory.inspect(None)
            self.next_memory = time.monotonic() + 1
            if reason:
                raise ValueError(reason)

    def command(self, arguments, label, timeout=20, required=True, environment=None):
        """Bound output/time and retain failure output without printing environment or keys."""
        self.check()
        self.sequence += 1
        path = self.directory / f'{self.sequence:03d}-{label}.log'
        step = dict(label=label, started_at=self.ci.timestamp(), log=path.name, exit_code=None)
        self.record['steps'].append(step)
        self.save()
        process = None
        end = min(self.deadline, time.monotonic()+timeout)
        try:
            with path.open('wb') as output:
                process = subprocess.Popen([str(x) for x in arguments], stdout=output, stderr=subprocess.STDOUT,
                                           stdin=subprocess.DEVNULL, env=environment, start_new_session=True)
                while process.poll() is None:
                    self.check()
                    if time.monotonic() >= end:
                        raise CommandTimeout(f'{label}: command deadline exceeded')
                    if path.stat().st_size > 2 * 1024 * 1024:
                        raise ValueError(f'{label}: diagnostic output exceeded2MiB')
                    time.sleep(.1)
                step['exit_code'] = process.returncode
            if path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError(f'{label}: diagnostic output exceeded2MiB')
            value = path.read_text(encoding='utf-8', errors='replace')
            if required and process.returncode:
                raise ValueError(f'{label}: exit{process.returncode}; retained {path.name}')
            return value
        finally:
            if process is not None and process.poll() is None:
                self.ci.stop_process(process)
                step['exit_code'] = process.returncode
            step['finished_at'] = self.ci.timestamp()
            if path.exists(): step['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            self.save()

    def shell(self, command, label, timeout=15, required=True):
        return self.command([self.adb, '-s', self.serial, 'shell']+command, label, timeout, required)

    def build_probe(self):
        source = Path(__file__).with_name('api26-profile')
        tools = self.sdk / 'build-tools/35.0.0'
        android = self.sdk / 'platforms/android-36/android.jar'
        for path in (tools/'aapt2', tools/'d8', tools/'apksigner', android):
            if not path.is_file(): raise ValueError(f'Missing SDK prerequisite: {path}')
        build = self.temporary / 'probe'
        build.mkdir()
        classes, dex = build/'classes', build/'dex'
        classes.mkdir(); dex.mkdir()
        unsigned, signed = build/'unsigned.apk', build/'probe.apk'
        self.command([tools/'aapt2', 'link', '-I', android, '--manifest', source/'AndroidManifest.xml',
                      '-o', unsigned], 'probe-package', 30)
        self.command(['javac', '-source', '8', '-target', '8', '-classpath', android, '-d', classes,
                      source/'ProfileProbe.java'], 'probe-compile', 30)
        self.command([tools/'d8', '--min-api', '26', '--lib', android, '--output', dex,
                      classes/'org/lifetracing/ciprofile/ProfileProbe.class'], 'probe-dex', 30)
        with zipfile.ZipFile(unsigned, 'a') as archive: archive.write(dex/'classes.dex', 'classes.dex')
        # A disposable diagnostic signing key, never a product key; kept outside uploaded evidence.
        key = build/'diagnostic.jks'
        self.command(['keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
                      '-alias', 'diagnostic', '-dname', 'CN=Disposable CI profile', '-keyalg', 'RSA',
                      '-validity', '2', '-noprompt'], 'probe-key', 30)
        self.command([tools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
                      '--out', signed, unsigned], 'probe-sign', 30)
        self.command([tools/'apksigner', 'verify', signed], 'probe-verify', 30)
        # Retain and install the same public artifact; the disposable signing key
        # remains in temporary storage and is removed by close().
        retained = self.directory/'profile-probe.apk'
        shutil.copyfile(signed, retained)
        self.record['probe'] = dict(apk_sha256=hashlib.sha256(retained.read_bytes()).hexdigest(),
                                   apk_path=retained.name,
                                   source_sha256=hashlib.sha256((source/'ProfileProbe.java').read_bytes()).hexdigest())
        return retained

    def setup(self):
        self.record['setup'] = dict(exit_code=None)
        command = ['./gradlew', ':data:pixel2Api26Setup',
                   '-Pandroid.testoptions.manageddevices.emulator.gpu=swangle',
                   '-Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5',
                   '--no-daemon', '--no-parallel', '--max-workers=2', '--no-configuration-cache',
                   '--rerun-tasks', '--no-build-cache', '--console=plain', '--info', '--stacktrace']
        self.ci.run_gradle(command, self.directory, 'setup', 26, self.record['setup'],
                           timeout=min(450, max(0, self.deadline-time.monotonic())), profile_memory=True)
        result = self.record['setup']
        if (result.get('exit_code') != 0 or result.get('abort_reason')
                or result.get('process_cleanup_passed') is not True or result.get('device_cleanup_passed') is not True):
            raise ValueError('API26 GMD profile setup did not finish with safe cleanup')
        self.avd = find_avd(self.avd_home)
        self.name = self.avd.stem
        self.record['avd_config_sha256'] = hashlib.sha256((self.avd/'config.ini').read_bytes()).hexdigest()
        self.record['avd_name'] = self.name

    def start_emulator(self, restore):
        self.ci.DeviceHealth(26)  # Refuse preexisting/ambiguous devices before owning a new emulator.
        label = 'restored' if restore else 'prepare'
        # A stopped emulator may leave TCP state after ADB has unregistered it.
        # Use a different pair for the second process; never reclaim an occupied port.
        port = 5582 if restore else 5580
        self.serial = f'emulator-{port}'
        self.record.setdefault('emulator_ports', {})[label] = port
        with socket.socket() as console, socket.socket() as transport:
            try:
                console.bind(('127.0.0.1', port)); transport.bind(('127.0.0.1', port+1))
            except OSError as exc:
                raise ValueError(f'{label}: emulator port pair {port}/{port+1} unavailable: {exc}') from exc
        self.emulator_path = self.directory / f'{label}-emulator.log'
        self.emulator_log = self.emulator_path.open('wb')
        environment = dict(os.environ, ANDROID_AVD_HOME=str(self.avd.parent))
        command = [str(self.sdk/'emulator/emulator'), '-avd', self.name, '-gpu', 'swangle', '-no-window',
                   '-no-audio', '-no-boot-anim', '-no-snapshot-save', '-port', str(port)]
        command += ['-snapshot', 'default_boot', '-read-only'] if restore else ['-no-snapshot-load']
        self.emulator = subprocess.Popen(command, env=environment, stdout=self.emulator_log,
                                         stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        self.boot(label)

    def boot(self, label):
        end = min(self.deadline, time.monotonic()+120)
        while time.monotonic() < end:
            self.check()
            if self.emulator.poll() is not None: raise ValueError('Owned profile emulator exited before boot')
            try:
                value = self.shell(['getprop', 'sys.boot_completed'], label+'-boot', timeout=3, required=False)
            except CommandTimeout:
                continue  # The partial timeout log is retained; the120s boot bound still applies.
            if value.strip() == '1':
                if self.shell(['getprop', 'ro.build.version.sdk'], label+'-api').strip() != '26':
                    raise ValueError('Profile emulator API mismatch')
                avd = self.command([self.adb, '-s', self.serial, 'emu', 'avd', 'name'], label+'-avd')
                if avd.splitlines()[:1] != [self.name]: raise ValueError('Profile emulator AVD identity mismatch')
                window = self.shell(['dumpsys', 'window'], label+'-window')
                dropbox = self.shell(['dumpsys', 'dropbox', '--print', 'system_app_anr'], label+'-anr')
                if 'Application Not Responding' in window or re.search(r'Process:\s*com\.android\.systemui\b', dropbox):
                    raise ValueError('Profile boot has a SystemUI ANR')
                return
            time.sleep(.5)
        raise ValueError('Profile boot deadline exceeded')

    def guest_server(self, label):
        pid = self.shell(['pidof', 'system_server'], label+'-server').strip()
        if not pid.isdigit(): raise ValueError('Expected one guest system_server')
        stat = self.shell(['su', '0', 'cat', '/proc/'+pid+'/stat'], label+'-identity')
        return dict(pid=int(pid), start=stat.rsplit(')', 1)[1].split()[19])

    def configure_guest(self):
        old = self.guest_server('old')
        if self.shell(['su', '0', 'id', '-u'], 'guest-root').strip() != '0':
            raise ValueError('Disposable AOSP guest does not support su0')
        self.shell(['su', '0', 'setprop', 'persist.enable_task_snapshots', 'false'], 'set-profile')
        if self.shell(['getprop', 'persist.enable_task_snapshots'], 'property-readback').strip() != 'false':
            raise ValueError('Task-snapshot property readback failed')
        services = [name for name in ('zygote', 'zygote_secondary')
                    if self.shell(['getprop', 'init.svc.'+name], 'service-'+name).strip() == 'running']
        if 'zygote' not in services: raise ValueError('Guest zygote service is missing')
        self.shell(['su', '0', 'setprop', 'sys.boot_completed', '0'], 'clear-boot')
        for name in services: self.shell(['su', '0', 'setprop', 'ctl.stop', name], 'stop-'+name)
        end = min(self.deadline, time.monotonic()+15)
        while time.monotonic() < end:
            absent = self.shell(['pidof system_server zygote zygote64 || true'], 'framework-absence').strip()
            if not absent: break
            time.sleep(.3)
        else: raise ValueError('Guest framework absence not confirmed')
        for name in services: self.shell(['su', '0', 'setprop', 'ctl.start', name], 'start-'+name)
        self.boot('configured')
        new = self.guest_server('new')
        if new == old: raise ValueError('Guest system_server lifetime did not change')
        self.record.update(old_guest_server=old, new_guest_server=new, framework_absence_confirmed=True)

    def field(self, apk, label):
        installed = self.command([self.adb, '-s', self.serial, 'install', '-r', '-t', apk], label+'-install', 30)
        if 'Success' not in installed: raise ValueError('Profile diagnostic APK installation not confirmed')
        output = self.shell(['am', 'instrument', '-w', '-r', PROBE], label+'-field', 30)
        self.record[label] = verify_probe(output, scaled_raster=self.scaled_raster)
        surface = self.shell(['dumpsys', 'SurfaceFlinger'], label+'-surface')
        gles = [line.strip() for line in surface.splitlines() if 'GLES:' in line]
        if len(gles) != 1 or 'ANGLE' not in gles[0] or 'SwiftShader' not in gles[0]:
            raise ValueError('Actual SwANGLE renderer not confirmed')
        self.record[label]['gles'] = gles[0]
        if 'Success' not in self.command([self.adb, '-s', self.serial, 'uninstall', PACKAGE], label+'-uninstall'):
            raise ValueError('Profile diagnostic APK uninstall not confirmed')
        self.save()

    def configure_raster(self):
        if not self.scaled_raster: return
        # Only the disposable guest before product tests. The ratios preserve
        # logical dimensions: 720/280=1080/420 and1280/280=1920/420.
        self.shell(['wm', 'size', '720x1280'], 'scaled-size')
        self.shell(['wm', 'density', '280'], 'scaled-density')
        size = self.shell(['wm', 'size'], 'scaled-size-readback')
        density = self.shell(['wm', 'density'], 'scaled-density-readback')
        if (re.findall(r'^Override size: (.*)$', size, re.MULTILINE) != ['720x1280']
                or re.findall(r'^Override density: (.*)$', density, re.MULTILINE) != ['280']):
            raise ValueError('Owned guest raster overrides were not applied')

    def stop_emulator(self):
        self.command([self.adb, '-s', self.serial, 'emu', 'kill'], 'emulator-stop')
        self.emulator.wait(timeout=min(25, max(.1, self.deadline-time.monotonic())))
        self.emulator_log.close()
        self.emulator_log = None
        # Emulator exit and ADB registration removal are asynchronous. Wait only
        # for our stopped serial; a different emulator is never admitted/touched.
        end = min(self.deadline, time.monotonic()+10)
        while time.monotonic() < end:
            output = self.command([self.adb, 'devices'], 'emulator-unregistration', 3)
            devices = [parts[0] for line in output.splitlines()
                       if len(parts := line.split()) >= 2 and re.fullmatch(r'emulator-\d+', parts[0])]
            if not devices: return
            if devices != [self.serial]: raise ValueError('Foreign emulator appeared during profile cleanup')
            time.sleep(.2)
        raise ValueError('Stopped profile emulator did not unregister from ADB')

    def prepare(self):
        try:
            if self.ci.emulator_revision()['revision'] != '37.2.13':
                raise ValueError('API26 profile was validated only with Emulator37.2.13')
            version = self.command([self.sdk/'emulator/emulator', '-no-window', '-no-audio', '-version'], 'emulator-version')
            if '37.2.13.0 (build_id 16740729)' not in version:
                raise ValueError('Actual emulator build does not match the measured16740729 binary')
            self.setup()
            apk = self.build_probe()
            self.start_emulator(False)
            self.configure_guest()
            self.configure_raster()
            self.field(apk, 'prepared')
            receipt = self.command([self.adb, '-s', self.serial, 'emu', 'avd', 'snapshot', 'save', 'default_boot'],
                                   'snapshot-save', 45)
            snapshot = self.avd/'snapshots/default_boot/snapshot.pb'
            if 'KO:' in receipt or 'OK' not in receipt.splitlines() or not snapshot.is_file() or not snapshot.stat().st_size:
                raise ValueError('Prepared snapshot save was not confirmed')
            self.stop_emulator()
            # This is evidence at save time, not an immutable hash gate across loads.
            self.record['saved_snapshot_sha256'] = hashlib.sha256(snapshot.read_bytes()).hexdigest()
            environment = dict(os.environ, ANDROID_AVD_HOME=str(self.avd.parent))
            self.command([self.sdk/'emulator/emulator', '-avd', self.name, '-gpu', 'swangle', '-no-window',
                          '-read-only', '-no-snapshot-save', '-check-snapshot-loadable', 'default_boot'],
                         'snapshot-loadability', 30, environment=environment)
            self.start_emulator(True)
            self.field(apk, 'restored')
            verify_restore(self.emulator_path.read_text(encoding='utf-8', errors='replace'))
            self.stop_emulator()
            self.record.update(status='PREPARED', prepared_at=self.ci.timestamp())
        except Exception as exc:
            self.record.update(status='FAILED', error=str(exc))
            raise
        finally:
            self.save()

    def close(self):
        safe = self.owned.cleanup()
        if self.emulator_log is not None: self.emulator_log.close()
        if self.old_avd_home is None: os.environ.pop('ANDROID_AVD_HOME', None)
        else: os.environ['ANDROID_AVD_HOME'] = self.old_avd_home
        self.record['cleanup_passed'] = safe
        if safe:
            if self.temporary.is_symlink() or self.temporary.resolve() != self.temporary:
                raise ValueError('Owned temporary profile directory identity changed')
            shutil.rmtree(self.temporary)
        self.record['artifacts'] = {p.relative_to(self.directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in self.directory.rglob('*') if p.is_file() and p.name != 'profile.json'}
        self.save()
        return safe
