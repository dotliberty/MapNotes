"""No real keys are opened or created by these signing-policy regressions."""

import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import build


class DebugSigningTest(unittest.TestCase):
    def test_missing_key_fails_closed(self):
        with patch.object(
            Path, "home", return_value=build.ROOT.parent / "mock-home"
        ), patch.object(Path, "is_file", return_value=False):
            with self.assertRaisesRegex(ValueError, "No key was generated"):
                build.debug_keystore()

    def test_existing_identity_is_stable_and_outside_checkout(self):
        home = build.ROOT.parent / "mock-home"
        with patch.object(Path, "home", return_value=home), patch.object(
            Path, "is_file", return_value=True
        ):
            self.assertEqual(home / ".android/debug.keystore", build.debug_keystore())
            self.assertEqual(build.debug_keystore(), build.debug_keystore())

    def test_in_checkout_identity_is_rejected(self):
        with patch.object(Path, "home", return_value=build.ROOT):
            with self.assertRaisesRegex(ValueError, "outside"):
                build.debug_keystore()

    def test_unreadable_or_wrong_password_never_regenerates_key(self):
        with patch("sys.argv", ["build.py", "--debug"]), patch.dict(
            os.environ, {"JAVA_HOME": "mock-jdk", "ANDROID_HOME": "mock-sdk"}
        ), patch.object(
            build, "debug_keystore", return_value=Path("mock-debug.keystore")
        ), patch.object(
            subprocess, "run", side_effect=subprocess.CalledProcessError(1, "keytool")
        ) as runner:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build()
            self.assertEqual(1, runner.call_count)
            command = runner.call_args.args[0]
            self.assertIn("-list", command)
            self.assertIn("androiddebugkey", command)
            self.assertNotIn("-genkeypair", command)
            self.assertTrue(runner.call_args.kwargs["check"])

    def test_release_arguments_not_allowed_in_debug(self):
        with patch("sys.argv", ["build.py", "--debug", "--keystore", "not-read.jks"]):
            with self.assertRaises(SystemExit) as error:
                build.build()
            self.assertEqual(2, error.exception.code)

    def test_release_arguments_not_allowed_in_nondebug_test(self):
        with patch(
            "sys.argv", ["build.py", "--non-debug-test", "--keystore", "not-read.jks"]
        ):
            with self.assertRaises(SystemExit) as error:
                build.build()
            self.assertEqual(2, error.exception.code)

    def test_isolated_variants_are_mutually_exclusive(self):
        with patch("sys.argv", ["build.py", "--debug", "--non-debug-test"]):
            with self.assertRaises(SystemExit) as error:
                build.build()
            self.assertEqual(2, error.exception.code)

    def test_nondebug_test_validates_existing_identity_before_build(self):
        with patch("sys.argv", ["build.py", "--non-debug-test"]), patch.dict(
            os.environ, {"JAVA_HOME": "mock-jdk", "ANDROID_HOME": "mock-sdk"}
        ), patch.object(
            build, "debug_keystore", return_value=Path("mock-debug.keystore")
        ), patch.object(
            subprocess, "run", side_effect=subprocess.CalledProcessError(1, "keytool")
        ) as runner:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build()
            self.assertEqual(1, runner.call_count)
            self.assertIn("-list", runner.call_args.args[0])
            self.assertNotIn("-genkeypair", runner.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
