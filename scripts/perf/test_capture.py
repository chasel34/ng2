import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from capture import capture, focus_info, package_info


FAKE_ADB = '''#!/usr/bin/env python3
import shlex
import sys
from pathlib import Path

args = sys.argv[3:]
if args[0] == "pull":
    Path(args[2]).write_bytes(b"fake trace")
    sys.exit(0)
command = shlex.split(args[1])
if command[:1] == ["getprop"]:
    print("1" if command[1] == "ro.kernel.qemu" else "fixture")
elif command[:2] == ["dumpsys", "package"]:
    print("Package [com.chasel.ng2] (abc):\\n  versionCode=42 minSdk=31\\n  versionName=1.2\\n  flags=[ HAS_CODE DEBUGGABLE ]")
elif command[:2] == ["dumpsys", "window"]:
    print("mCurrentFocus=Window{abc u0 com.chasel.ng2.dev/.MainActivity}")
elif command[:2] == ["dumpsys", "display"]:
    sys.exit(1)
elif command[0] == "perfetto":
    sys.stdin.read()
    if Path(sys.argv[0]).with_suffix(".fail").exists():
        print("trace failed", file=sys.stderr)
        sys.exit(1)
elif command[:2] == ["dumpsys", "gfxinfo"]:
    if not Path(sys.argv[0]).with_suffix(".empty").exists():
        print("---PROFILEDATA---\\nFlags,IntendedVsync\\n---PROFILEDATA---")
'''


class CaptureTest(unittest.TestCase):
    def test_unknown_package_flags_stay_unknown(self):
        self.assertIsNone(package_info("permission denied", "com.chasel.ng2")["installed"])
        self.assertIsNone(package_info("Package [com.chasel.ng2]", "com.chasel.ng2")["debuggable"])
        self.assertFalse(package_info("Package [com.chasel.ng2]\n flags=[ HAS_CODE ]", "com.chasel.ng2")["debuggable"])

    def test_focus_matches_exact_package_and_handles_unknown(self):
        self.assertFalse(focus_info("mCurrentFocus=Window{x u0 com.chasel.ng2.dev/.Main}", "com.chasel.ng2")["target_focused"])
        self.assertTrue(focus_info("mCurrentFocus=Window{x u0 com.chasel.ng2/.Main}", "com.chasel.ng2")["target_focused"])
        self.assertIsNone(focus_info("mCurrentFocus=null", "com.chasel.ng2")["target_focused"])
        self.assertIsNone(focus_info("mCurrentFocus=Window{x u0 com.chasel.ng2/.Main}\nmCurrentFocus=null", "com.chasel.ng2")["target_focused"])

    def run_capture(self, root, kind):
        executable = root / "fake-adb"
        executable.write_text(FAKE_ADB)
        executable.chmod(0o755)
        args = argparse.Namespace(
            adb=str(executable), serial="fixture-device", package="com.chasel.ng2",
            output=root / "sample", kind=kind, seconds=1, scene="fixture",
            build_note=None,
        )
        with contextlib.redirect_stdout(io.StringIO()):
            result = capture(args)
        return args, result, json.loads((args.output / "metadata.json").read_text())

    def test_evidence_does_not_certify_debug_emulator_or_wrong_focus(self):
        with tempfile.TemporaryDirectory() as directory:
            args, result, metadata = self.run_capture(Path(directory), "framestats")
            self.assertEqual(result, 0)
            self.assertEqual(metadata["source_hint"], "emulator")
            self.assertTrue(metadata["app"]["debuggable"])
            self.assertEqual(metadata["app"]["version_code"], "42")
            for snapshot in metadata["snapshots"].values():
                self.assertFalse(snapshot["target_focused"])
                self.assertIsNone(snapshot["target_refresh_hz"])
                self.assertEqual(snapshot["display_evidence"], [])
            self.assertTrue(any(row["returncode"] == 1 for row in metadata["commands"]))
            self.assertIsNone(metadata["release_build_verified"])
            self.assertEqual(metadata["performance_verdict"], "unassessed")
            with self.assertRaises(FileExistsError):
                capture(args)

    def test_trace_config_pull_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            args, result, metadata = self.run_capture(Path(directory), "frametimeline")
            self.assertEqual(result, 0)
            self.assertIn("duration_ms: 1000", (args.output / "capture.cfg").read_text())
            self.assertEqual((args.output / "trace.pb").read_bytes(), b"fake trace")
            self.assertEqual(metadata["status"], "captured")
            self.assertTrue((args.output / "cleanup.txt").exists())

    def test_failed_trace_keeps_incomplete_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fake-adb.fail").touch()
            args, result, metadata = self.run_capture(root, "frametimeline")
            self.assertEqual(result, 1)
            self.assertEqual(metadata["status"], "incomplete")
            self.assertIn("Perfetto", metadata["error"])
            self.assertFalse((args.output / "trace.pb").exists())

    def test_empty_successful_adb_response_is_not_captured(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fake-adb.empty").touch()
            _, result, metadata = self.run_capture(root, "framestats")
            self.assertEqual(result, 1)
            self.assertEqual(metadata["status"], "incomplete")
            self.assertIn("framestats", metadata["error"])


if __name__ == "__main__":
    unittest.main()
