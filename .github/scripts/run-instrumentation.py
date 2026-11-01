"""Run full GMD suites and independent startup sequentially; retain JUnit and native diagnostics."""

import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import select
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def process_label(pid):
    """Public process identity only. Never read argv, environment or credentials."""
    try:
        text = Path(f"/proc/{pid}/stat").read_text()
        comm, fields = text.split("(", 1)[1].rsplit(")", 1)
        fields = fields.split()
        return dict(pid=int(pid), comm=comm, parent=int(fields[1]), start=fields[19], state=fields[0])
    except (OSError, ValueError, IndexError):
        return None


def signal_event(line):
    match = re.search(r"^\s*(.+)-(\d+)\s+\[\d+\].*?\s(\d+\.\d+):\s+signal_generate: "
                      r"sig=(9|15) errno=(-?\d+) code=(-?\d+) comm=(.*?) pid=(\d+) grp=(\d+) res=(\d+)", line)
    if match is None:
        return None
    sender, tid, trace_time, sig, error, code, target, pid, group, result = match.groups()
    return dict(sender_comm=sender.strip(), sender_tid=int(tid), trace_seconds=trace_time,
                signal=int(sig), errno=int(error), code=int(code),
                target_comm=target, target_pid=int(pid), group=int(group), result=int(result))


class HostSignalObserver:
    """API26-only diagnostic sidecar; never intercept or forward workload signals."""
    def __init__(self, directory):
        self.directory = directory.resolve() / ("host-signals-" + uuid.uuid4().hex)
        self.directory.mkdir()
        self.process = None

    def start(self):
        if sys.platform != "linux":
            raise RuntimeError("Host signal observation requires Linux tracefs")
        owner = process_label(os.getpid())
        if owner is None:
            raise RuntimeError("Cannot identify signal observer owner")
        command = [sys.executable, "-u", str(Path(__file__).resolve()), "--host-signal-observer",
                   str(self.directory), str(os.getpid()), owner["start"]]
        if os.geteuid() != 0:
            command = ["sudo", "-n", "--"] + command
        self.process = subprocess.Popen(command, start_new_session=True)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            ready = self.directory / "ready.json"
            if ready.is_file():
                result = json.loads(ready.read_text())
                if result.get("calibrated") is True and self.process.poll() is None:
                    return result
                break
            if self.process.poll() is not None:
                break
            time.sleep(0.1)
        self.close()
        raise RuntimeError("API26 signal observer failed its live calibration; see host-signals log")

    def close(self):
        (self.directory / "stop").touch()
        if self.process is None:
            return False
        try:
            return self.process.wait(timeout=10) == 0
        except subprocess.TimeoutExpired:
            # Root sidecar also exits on owner disappearance or its one-hour limit.
            print("::error::Host signal observer did not acknowledge cleanup", flush=True)
            return False


def observe_host_signals(directory, owner_pid, owner_start):
    """Isolated, bounded trace instance. Live output survives skipped artifact uploads.

    Trace records identify kernel tasks, not the upstream initiator of a VM loss.
    A calibration establishes whether kernel IDs match this /proc PID namespace.
    """
    directory = Path(directory)
    instance = Path("/sys/kernel/tracing/instances") / ("lt26-" + uuid.uuid4().hex)
    pipe, child = None, None
    output = (directory / "events.jsonl").open("x", encoding="utf-8", buffering=1)
    pending = b""
    emitted = 0
    truncated = False
    calibrated = False
    direct_pids = False
    result = 1
    stop_signal = None
    previous_handlers = {}

    def request_stop(sig, frame):
        nonlocal stop_signal
        stop_signal = sig  # Drain the sidecar's buffer; workload handlers stay untouched.

    def emit(kind, **data):
        nonlocal emitted, truncated
        if emitted >= 4096:
            if not truncated:
                truncated = True
                line = json.dumps(dict(kind="truncated", at=timestamp(), limit=4096))
                output.write(line + "\n")
                print("host-signal " + line, flush=True)
            return
        emitted += 1
        line = json.dumps(dict(kind=kind, at=timestamp(), **data))
        output.write(line + "\n")
        print("host-signal " + line, flush=True)

    def read_events(timeout):
        nonlocal pending
        events = []
        if select.select([pipe], [], [], timeout)[0]:
            chunk = os.read(pipe, 65536)
            pending += chunk
            lines = pending.split(b"\n")
            pending = lines.pop()[-4096:]
            for raw in lines:
                line = raw.decode("utf-8", "replace")
                event = signal_event(line)
                if event is not None:
                    if direct_pids:
                        event["sender_identity"] = process_label(event["sender_tid"])
                        event["target_identity"] = process_label(event["target_pid"])
                    events.append(event)
                    emit("signal", **event)
                elif "mark_victim:" in line:
                    emit("oom_victim", trace=line.strip()[:512])
        return events

    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous_handlers[sig] = signal.signal(sig, request_stop)
        owner = process_label(owner_pid)
        if not owner or owner["start"] != owner_start or owner["state"] == "Z":
            raise RuntimeError("Signal observer owner is absent or changed")
        instance.mkdir()  # Never change another instance or the global tracer.
        (instance / "buffer_size_kb").write_text("128")
        (instance / "events/signal/signal_generate/filter").write_text("sig == 9 || sig == 15")
        (instance / "events/signal/signal_generate/enable").write_text("1")
        oom = instance / "events/oom/mark_victim/enable"
        if oom.exists():
            oom.write_text("1")
        (instance / "tracing_on").write_text("1")
        pipe = os.open(instance / "trace_pipe", os.O_RDONLY | os.O_NONBLOCK)
        name = "lt26c" + uuid.uuid4().hex[:8]
        dummy = ("import ctypes,signal; "
                 f"assert ctypes.CDLL(None).prctl(15, {name.encode()!r}, 0, 0, 0)==0; "
                 "print('ready',flush=True); signal.pause()")
        child = subprocess.Popen([sys.executable, "-c", dummy], stdout=subprocess.PIPE, text=True)
        if not select.select([child.stdout], [], [], 5)[0] or child.stdout.readline().strip() != "ready":
            raise RuntimeError("Calibration child did not become ready")
        child.terminate()  # Only this known dummy receives a diagnostic signal.
        if child.wait(timeout=5) != -signal.SIGTERM:
            raise RuntimeError("Calibration child did not terminate by SIGTERM")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not calibrated:
            for event in read_events(0.1):
                if event["target_comm"] == name and event["signal"] == 15 and event["result"] == 0:
                    calibrated = True
                    direct_pids = event["target_pid"] == child.pid and event["sender_tid"] == os.getpid()
        if not calibrated:
            raise RuntimeError("Known SIGTERM was not observed")
        ready = dict(calibrated=True, direct_proc_ids=direct_pids, owner=owner,
                     observer=process_label(os.getpid()), instance=instance.name, oom_enabled=oom.exists())
        emit("ready", **ready)
        temp = directory / "ready.tmp"
        temp.write_text(json.dumps(ready), encoding="utf-8")
        temp.replace(directory / "ready.json")
        deadline = time.monotonic() + 3600
        while time.monotonic() < deadline:
            read_events(0.2)
            current = process_label(owner_pid)
            if stop_signal is not None:
                emit("observer_signalled", signal=stop_signal)
                result = 128 + stop_signal
                break
            if (directory / "stop").exists():
                result = 0
                break
            if not current or current["start"] != owner_start or current["state"] == "Z":
                emit("owner_exited")
                result = 0
                break
        else:
            emit("deadline", seconds=3600)
    except Exception as exc:
        emit("observer_error", error=f"{type(exc).__name__}: {exc}")
    finally:
        if child is not None:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)
            child.stdout.close()
        try:
            if instance.exists():
                (instance / "tracing_on").write_text("0")
                if pipe is not None:
                    read_events(0)
                    os.close(pipe)
                    pipe = None
                stats = {p.parent.name: p.read_text() for p in (instance / "per_cpu").glob("cpu*/stats")}
                (directory / "buffer-stats.json").write_text(json.dumps(stats), encoding="utf-8")
                instance.rmdir()
            emit("stopped", calibrated=calibrated, instance_removed=not instance.exists(), truncated=truncated)
        except OSError as exc:
            result = 1
            emit("cleanup_error", error=str(exc))
        finally:
            if pipe is not None:
                os.close(pipe)
            for sig, handler in previous_handlers.items():
                signal.signal(sig, handler)
            output.close()
    return result


def adb_command():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb") if sdk else None
    adb = str(adb) if adb is not None and adb.is_file() else shutil.which("adb")
    if adb is None:
        raise ValueError("Cannot locate adb for device health admission")
    return adb


class DeviceHealth:
    """Observe the invocation's exclusive emulator, including setup/snapshot boot.

    A dedicated CI runner must start with no emulators. Never touch a preexisting,
    ambiguous or API-mismatched device. Commands are read-only until owned cleanup.
    """
    def __init__(self, api):
        self.adb, self.api = adb_command(), api
        self.serial, self.avd = None, None
        self.observations = []
        self.pending_since = None
        if self.devices():
            raise ValueError("Device admission refuses preexisting emulators")

    def read(self, arguments):
        completed = subprocess.run([self.adb] + arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, encoding="utf-8", errors="replace", timeout=5, check=False)
        self.observations.append(dict(command=[self.adb] + arguments, at=timestamp(),
                                      return_code=completed.returncode, stdout=completed.stdout, stderr=completed.stderr))
        # Retain a bounded rolling sample; the abort capture is stored independently.
        self.observations = self.observations[-12:]
        if completed.returncode:
            raise ValueError(f"Device health command failed: {arguments}: {completed.stderr}")
        return completed.stdout

    def devices(self):
        return [(parts[0], parts[1]) for line in self.read(["devices"]).splitlines()
                if len(parts := line.split()) >= 2 and re.fullmatch(r"emulator-\d+", parts[0])]

    def pending(self, reason):
        # Match GMD's five-minute setup allowance, including snapshot boot transitions.
        if self.pending_since is None:
            self.pending_since = time.monotonic()
        if time.monotonic() - self.pending_since >= 300:
            return f"Device health unavailable for 300 seconds: {reason}"
        return None

    def inspect(self):
        try:
            devices = self.devices()
            if not devices:
                if self.serial is not None or self.pending_since is not None:
                    return self.pending("emulator disconnected")
                return None  # Gradle may still be compiling or between setup and test boots.
            if len(devices) != 1:
                return "Device admission found ambiguous emulators"
            serial, state = devices[0]
            # An emulator briefly registers offline while booting. Do not select it.
            if state != "device":
                return self.pending(f"emulator is {state}")
            actual = self.read(["-s", serial, "shell", "getprop", "ro.build.version.sdk"]).strip()
            if not actual:
                return self.pending("API property is not available")
            if actual != str(self.api):
                return f"Device admission API mismatch: expected {self.api}, observed {actual!r}"
            avd = self.read(["-s", serial, "emu", "avd", "name"]).splitlines()[0].strip()
            if not avd or avd == "OK":
                return "Device admission cannot identify the AVD"
            if self.avd is not None and avd != self.avd:
                return "Device identity changed during the invocation"
            self.serial, self.avd = serial, avd
            booted = self.read(["-s", serial, "shell", "getprop", "sys.boot_completed"]).strip() == "1"
            # lastanr is deliberately not an admission signal: CI proved it can be
            # empty while a saved SystemUI startup ANR still owns window focus.
            window = self.read(["-s", serial, "shell", "dumpsys", "window"])
            if "Application Not Responding" in window:
                return "Device admission found an ANR window"
            dropbox = self.read(["-s", serial, "shell", "dumpsys", "dropbox", "--print", "system_app_anr"])
            if re.search(r"Process:\s*com\.android\.systemui\b", dropbox):
                return "Device admission found a persistent SystemUI startup ANR"
            if not booted:
                return self.pending("boot is not complete")
        except (OSError, ValueError, subprocess.TimeoutExpired, IndexError) as exc:
            return self.pending(str(exc))
        self.pending_since = None
        return None

    def cleanup(self):
        if self.serial is None:
            return not self.devices()
        devices = self.devices()
        if not any(s == self.serial for s, _ in devices):
            return True
        # Revalidate before the only mutating adb command. Never dismiss the ANR.
        actual = self.read(["-s", self.serial, "shell", "getprop", "ro.build.version.sdk"]).strip()
        avd = self.read(["-s", self.serial, "emu", "avd", "name"]).splitlines()[0].strip()
        if actual != str(self.api) or avd != self.avd:
            return False
        self.read(["-s", self.serial, "emu", "kill"])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not any(s == self.serial for s, _ in self.devices()):
                return True
            time.sleep(0.2)
        return False


def linux_descendants():
    """Snapshot this runner's children, including orphaned single-use Gradle daemons."""
    processes = {}
    for entry in Path("/proc").glob("[0-9]*/stat"):
        try:
            fields = entry.read_text().rsplit(")", 1)[1].split()
            processes[int(entry.parent.name)] = (int(fields[1]), fields[19], fields[0])
        except (FileNotFoundError, ProcessLookupError):
            continue
    owned, parents = {}, {os.getpid()}
    while True:
        children = {pid for pid, (parent, _, _) in processes.items() if parent in parents} - parents
        if not children:
            break
        for pid in children:
            owned[pid] = processes[pid]
        parents.update(children)
    return owned


class OwnedProcesses:
    def __init__(self):
        self.linux = sys.platform == "linux"
        self.baseline = {}
        if self.linux:
            # Gradle may detach its single-use daemon into a different session.
            # Adopt its orphaned descendants so cleanup cannot lose that ancestry.
            if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
                raise OSError(ctypes.get_errno(), "Cannot establish owned-process cleanup")
            self.baseline = linux_descendants()

    def cleanup(self):
        if not self.linux:
            return True
        started = time.monotonic()
        while True:
            children = {pid: info for pid, info in linux_descendants().items()
                        if self.baseline.get(pid, (None, None))[1] != info[1]}
            live = {pid: info for pid, info in children.items() if info[2] != "Z"}
            for pid, (_, _, state) in children.items():
                if state == "Z":
                    try:
                        os.waitpid(pid, os.WNOHANG)
                    except ChildProcessError:
                        pass  # Still belongs to a live parent; no execution remains.
            if not live:
                return True
            elapsed = time.monotonic() - started
            if elapsed >= 10:
                return False
            for pid, identity in live.items():
                # Recheck ancestry and start time against PID reuse before signaling.
                observed = linux_descendants().get(pid)
                if observed is not None and observed[:2] == identity[:2]:
                    try:
                        os.kill(pid, signal.SIGTERM if elapsed < 5 else signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            time.sleep(0.1)


def stop_process(process):
    """Bound termination of our process/session, never all Java or emulator PIDs."""
    def send(sig):
        try:
            if os.name == "posix":
                os.killpg(process.pid, sig)
            elif sig == signal.SIGTERM:
                process.terminate()
            else:
                process.kill()
        except ProcessLookupError:
            pass
    send(signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        # The root is still alive and owns this group. After it exits, adopted
        # descendants are handled by OwnedProcesses without reusing a stale PGID.
        if os.name == "posix":
            send(signal.SIGKILL)
        else:
            process.kill()
        process.wait(timeout=5)
    return process.poll() is not None


def run_gradle(command, directory, module, api, record, timeout=3300):
    """Keep watching health/deadline even when Gradle emits no complete lines."""
    monitor = DeviceHealth(api)  # Reject foreign devices before launching Gradle.
    owned = OwnedProcesses()
    process, reader = None, None
    messages, finished = queue.Queue(maxsize=128), threading.Event()
    deadline = time.monotonic() + timeout
    next_health = 0

    def emit(value):
        while not finished.is_set():
            try:
                messages.put(value, timeout=0.1)
                return
            except queue.Full:
                pass

    def pump():
        try:
            for line in process.stdout:
                emit(line)
                if finished.is_set():
                    return
        except (OSError, ValueError) as exc:
            emit(exc)
        finally:
            emit(None)

    def abort(reason):
        record["abort_reason"] = reason
        print(f"::error::{module} API {api} runner abort: {reason}", flush=True)
        # Capture the live obstruction before any owned-process/device teardown.
        try:
            record["system_ui_diagnostics"] = capture_system_ui_diagnostics(directory, module, api)
        except (OSError, ValueError) as exc:
            record["system_ui_diagnostics_error"] = str(exc)

    try:
        with (directory / f"{module}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace", start_new_session=os.name == "posix")
            reader = threading.Thread(target=pump, daemon=True)
            reader.start()
            while True:
                now = time.monotonic()
                if now >= deadline:
                    abort("execution deadline exceeded")
                    break
                if now >= next_health:
                    reason = monitor.inspect()
                    next_health = time.monotonic() + 5
                    if reason:
                        abort(reason)
                        break
                try:
                    line = messages.get(timeout=min(1, max(0.001, deadline - time.monotonic())))
                except queue.Empty:
                    continue
                if line is None:
                    try:
                        record["exit_code"] = process.wait(timeout=min(1, max(0.001, deadline - time.monotonic())))
                    except subprocess.TimeoutExpired:
                        emit(None)
                        continue
                    break
                if isinstance(line, Exception):
                    raise line
                print(line, end="", flush=True)
                log.write(line)
                log.flush()
                if "Application Not Responding: com.android.systemui" in line:
                    record["system_ui_anr_observed"] = True
                    abort("SystemUI ANR window reported by instrumentation")
                    break
    finally:
        finished.set()
        close_error = None
        if process is not None:
            if record.get("exit_code") is None:
                try:
                    record["process_cleanup_passed"] = stop_process(process)
                    record["exit_code"] = process.returncode
                except (OSError, ValueError, IndexError, subprocess.TimeoutExpired) as exc:
                    record["process_cleanup_passed"] = False
                    record["cleanup_error"] = str(exc)
            else:
                record["process_cleanup_passed"] = True
            try:
                record["process_cleanup_passed"] &= owned.cleanup()
            except (OSError, ValueError, IndexError) as exc:
                record["process_cleanup_passed"] = False
                record["cleanup_error"] = str(exc)
            if reader is not None:
                reader.join(timeout=1)
            record["output_reader_stopped"] = reader is None or not reader.is_alive()
            if record["output_reader_stopped"]:
                try:
                    process.stdout.close()
                except (OSError, ValueError) as exc:
                    close_error = exc
            else:
                # Closing a pipe while an escaped descendant holds it can block on
                # the reader's I/O lock. Fail cleanup instead of an unbounded close.
                record["process_cleanup_passed"] = False
        try:
            record["device_cleanup_passed"] = monitor.cleanup()
        except (OSError, ValueError, subprocess.TimeoutExpired, IndexError) as exc:
            record["device_cleanup_passed"] = False
            record["device_cleanup_error"] = str(exc)
        record["device_health"] = dict(serial=monitor.serial, avd=monitor.avd, observations=monitor.observations)
        if close_error is not None:
            raise close_error


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


def quarantine_previous_outputs(repo, directory, module, api):
    """A failed build/admission must not certify a preceding invocation's XML."""
    target = "app" if module == "startup" else module
    base = repo / target / "build/outputs"
    paths = [base / f"androidTest-results/managedDevice/debug/pixel2Api{api}",
             base / f"managed_device_android_test_additional_output/debug/pixel2Api{api}",
             base / "androidTest-results/managedDevice/debug/test-result.pb"]
    for source in paths:
        source.resolve().relative_to((repo / target / "build").resolve())
        if source.exists():
            destination = directory / "previous" / module / source.relative_to(repo)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                raise ValueError(f"Refusing to overwrite previous invocation evidence: {destination}")
            shutil.move(str(source), str(destination))


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
    parser.add_argument("--trace-host-signals", action="store_true")
    args = parser.parse_args()
    if args.trace_host_signals and args.api != 26:
        parser.error("Host signal experiment is restricted to API26")
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
    # A successful API26 row took 50:46 (data alone 30:29). Share the budget
    # across suites, retaining five minutes inside the CI job for cleanup/uploads.
    row_deadline = time.monotonic() + 3300
    host_observer = None
    try:
        if args.trace_host_signals:
            host_observer = HostSignalObserver(directory)
            evidence["host_signal_observer"] = host_observer.start()
        if args.api == 26:
            # API 26's image omits the emulator dependency; AGP 8.10.1 queries it at setup line 194.
            subprocess.run(sdkmanager_command(), check=True, timeout=180)
        blocked = None
        for module in ("data", "app", "startup"):
            target_module = "app" if module == "startup" else module
            command = ["./gradlew", f":{target_module}:pixel2Api{args.api}DebugAndroidTest",
                       f"-Plifetracing.compatibility.expectedApi={args.api}",
                       # The indirect renderer is deprecated since Emulator 36.4.9.
                       "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader",
                       "-Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5",
                       "--no-parallel", "--max-workers=2", "--no-daemon", "--no-configuration-cache",
                       "--rerun-tasks", "--no-build-cache", "--console=plain", "--stacktrace"]
            if module == "startup":
                command.append("-Plifetracing.compatibility.productionStartup=true")
            record = dict(command=command, started_at=timestamp(), exit_code=None)
            evidence["modules"][module] = record
            if not blocked and time.monotonic() >= row_deadline:
                blocked = "row execution deadline"
                failed = True
            if blocked:
                record.update(status="NOT_RUN", started_at=None, finished_at=timestamp(),
                              blocked_by=blocked, mandatory_inventory_passed=False)
                continue
            print("command: " + " ".join(command), flush=True)
            # Separate invocations prevent shared AVD setup/teardown races, even on failure.
            try:
                quarantine_previous_outputs(repo, directory, module, args.api)
                run_gradle(command, directory, module, args.api, record,
                           timeout=max(0, row_deadline - time.monotonic()))
            except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
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
                      and "abort_reason" not in record
                      and record.get("process_cleanup_passed") is True
                      and record.get("device_cleanup_passed") is True
                      and not record.get("system_ui_anr_observed", False)
                      and record.get("observed_apis") == [args.api]
                      and not any(record.get(key, 0) for key in ("failures", "errors")))
            failed |= not passed
            record["status"] = "PASSED" if passed else "FAILED"
            if (record.get("abort_reason") or record.get("process_cleanup_passed") is not True
                    or record.get("device_cleanup_passed") is not True):
                blocked = module
            # Persist partial and failed admission promptly, not only after all suites.
            (directory / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            print(f"module={module} api={args.api} exit_code={record['exit_code']} "
                  f"tests={record.get('total', 0)} PASS={passed}", flush=True)
    finally:
        if host_observer is not None:
            stopped = host_observer.close()
            evidence.setdefault("host_signal_observer", {})["cleanup_passed"] = stopped
            failed |= not stopped
        evidence["finished_at"] = timestamp()
        (directory / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return int(failed)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--host-signal-observer"]:
        sys.exit(observe_host_signals(Path(sys.argv[2]), int(sys.argv[3]), sys.argv[4]))
    sys.exit(main())
