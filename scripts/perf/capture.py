#!/usr/bin/env python3
"""采集帧数据及设备证据；只记录采样条件，不自动裁决性能。"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def package_info(raw, package):
    if f"Package [{package}]" not in raw:
        return {"installed": None, "debuggable": None}
    flags = re.search(r"^\s*(?:pkgFlags|flags)=\[([^\]]*)\]", raw, re.M)
    version = re.search(r"versionName=(\S+)", raw)
    code = re.search(r"versionCode=(\d+)", raw)
    return {
        "installed": True,
        "debuggable": "DEBUGGABLE" in flags[1].split() if flags else None,
        "version_name": version[1] if version else None,
        "version_code": code[1] if code else None,
    }


def focus_info(raw, package):
    lines = [line.strip() for line in raw.splitlines() if "mCurrentFocus=" in line]
    packages = [re.search(r"\s([\w.]+)/", line) for line in lines]
    focused = [match[1] for match in packages if match]
    return {
        "evidence": lines,
        "target_focused": focused[0] == package if len(lines) == len(focused) == 1 else None,
    }


class Adb:
    def __init__(self, executable, serial, output):
        self.command = [executable, "-s", serial]
        self.output = output
        self.records = []

    def run(self, name, *args, required=False):
        record = {"file": name, "args": list(args), "started_at": utc_now()}
        try:
            result = subprocess.run(
                self.command + list(args), capture_output=True, text=True, timeout=15,
            )
            record.update(returncode=result.returncode, stderr=result.stderr)
            raw = result.stdout
        except (OSError, subprocess.TimeoutExpired) as error:
            raw = ""
            record.update(returncode=None, error=str(error))
        record["finished_at"] = utc_now()
        self.records.append(record)
        (self.output / name).write_text(raw)
        if required and record["returncode"] != 0:
            raise RuntimeError(f"adb 失败：{name}，详情见 metadata.json")
        return raw if record["returncode"] == 0 else ""

    def shell(self, name, *args, required=False):
        return self.run(name, "shell", shlex.join(args), required=required)


def snapshot(adb, label, package):
    started = utc_now()
    window = adb.shell(f"{label}-window.txt", "dumpsys", "window", "windows")
    display = adb.shell(f"{label}-display.txt", "dumpsys", "display")
    return {
        "started_at": started,
        "finished_at": utc_now(),
        **focus_info(window, package),
        "display_evidence": [line.strip() for line in display.splitlines()
                             if re.search(r"frameRateOverride|renderFrameRate|mActiveRenderFrameRate", line)],
        "target_refresh_hz": None,
    }


def capture(args):
    args.output.mkdir(parents=True, exist_ok=False)
    adb = Adb(args.adb, args.serial, args.output)
    metadata = {
        "status": "incomplete", "started_at": utc_now(), "serial": args.serial,
        "package": args.package, "kind": args.kind, "duration_seconds": args.seconds,
        "scene": args.scene, "build_note": args.build_note,
        "release_build_verified": None, "performance_verdict": "unassessed",
        "commands": adb.records, "snapshots": {},
    }
    process = None
    trace_log = None
    try:
        metadata["device"] = {
            key: adb.shell(f"device-{key}.txt", "getprop", prop).strip() or None
            for key, prop in {
                "model": "ro.product.model", "sdk": "ro.build.version.sdk",
                "fingerprint": "ro.build.fingerprint", "qemu": "ro.kernel.qemu",
            }.items()
        }
        qemu = metadata["device"]["qemu"]
        metadata["source_hint"] = {"0": "device", "1": "emulator"}.get(qemu, "unknown")
        raw = adb.shell("package.txt", "dumpsys", "package", args.package, required=True)
        metadata["app"] = package_info(raw, args.package)
        if metadata["app"]["installed"] is not True:
            raise RuntimeError("无法确认目标包已安装，见 package.txt")
        metadata["snapshots"]["before"] = snapshot(adb, "before", args.package)
        adb.shell("reset.txt", "dumpsys", "gfxinfo", args.package, "reset", required=True)
        if args.kind == "frametimeline":
            remote = f"/data/misc/perfetto-traces/ng2-{uuid.uuid4().hex}.pb"
            metadata["remote_trace"] = remote
            config = Path(__file__).with_name("frametimeline.cfg").read_text()
            config = re.sub(r"duration_ms:\s*\d+", f"duration_ms: {args.seconds * 1000}", config)
            (args.output / "capture.cfg").write_text(config)
            trace_log = (args.output / "perfetto.log").open("w")
            process = subprocess.Popen(
                adb.command + ["shell", shlex.join(["perfetto", "-c", "-", "--txt", "-o", remote])],
                stdin=subprocess.PIPE, stdout=trace_log, stderr=trace_log, text=True,
            )
            process.stdin.write(config)
            process.stdin.close()
        metadata["capture_started_at"] = utc_now()
        started = time.monotonic()
        print(f"开始采样 {args.seconds} 秒，请操作目标 app。", flush=True)
        time.sleep(args.seconds / 2)
        metadata["snapshots"]["during"] = snapshot(adb, "during", args.package)
        time.sleep(max(0, args.seconds - (time.monotonic() - started)))
        if process is not None:
            if process.wait(timeout=15) != 0:
                raise RuntimeError("Perfetto 采集失败，见 perfetto.log")
        metadata["capture_finished_at"] = utc_now()
        frames = adb.shell("framestats.txt", "dumpsys", "gfxinfo", args.package, "framestats", required=True)
        if "---PROFILEDATA---" not in frames:
            raise RuntimeError("未取得 framestats 数据块，见 framestats.txt")
        metadata["snapshots"]["after"] = snapshot(adb, "after", args.package)
        if process is not None:
            trace = args.output / "trace.pb"
            adb.run("pull.txt", "pull", remote, str(trace), required=True)
            if not trace.is_file() or trace.stat().st_size == 0:
                raise RuntimeError("拉取的 trace 为空")
            adb.shell("cleanup.txt", "rm", remote)
        metadata["status"] = "captured"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        metadata["error"] = str(error) or "采样被中断"
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        if trace_log is not None:
            trace_log.close()
        metadata["finished_at"] = utc_now()
        (args.output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(f"{metadata['status']}: {args.output.resolve()}；采样条件仍需按性能手册核对。")
    if metadata.get("error"):
        print(metadata["error"])
    return 0 if metadata["status"] == "captured" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="adb devices 中的设备序列号")
    parser.add_argument("--package", default="com.chasel.ng2")
    parser.add_argument("--kind", choices=["framestats", "frametimeline"], default="framestats")
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--scene", required=True, help="操作场景和运动窗口说明")
    parser.add_argument("--build-note", help="构建命令、提交或 APK 来源；仅记录调用者说明")
    parser.add_argument("--output", type=Path, required=True, help="新的采样目录，不覆盖已有目录")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 60:
        parser.error("--seconds 必须在 1 到 60 之间")
    if not re.fullmatch(r"[A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+", args.package):
        parser.error("--package 不是有效包名")
    try:
        return capture(args)
    except OSError as error:
        parser.exit(1, f"无法创建采样目录：{error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
