"""Build MapNotes with the Android SDK, optionally signing with your own key.
No signing credentials or private keys are stored in this repository.
"""

from pathlib import Path
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def valid_version(version):
    return bool(
        re.fullmatch(
            r"\d+\.\d+(?:\.\d+){0,2}(?:-(?:alpha|beta|rc)\.[1-9]\d*)?", version or ""
        )
    )


def debug_keystore():
    """Only the standard, persistent, user-local debug identity; never create/replace it."""
    path = Path.home() / ".android" / "debug.keystore"
    if path.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError("Debug keystore must be outside the checkout")
    if not path.is_file():
        raise ValueError(
            "Persistent debug keystore is missing; creation requires explicit owner approval. No key was generated."
        )
    return path


def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--gradle",
        default=os.environ.get("GRADLE") or shutil.which("gradle"),
        help="Gradle 8.9 executable (or set GRADLE)",
    )
    variants = parser.add_mutually_exclusive_group()
    variants.add_argument(
        "--debug",
        action="store_true",
        help="Separate .debug application using existing ~/.android/debug.keystore; never creates keys or reads release credentials",
    )
    variants.add_argument(
        "--non-debug-test",
        action="store_true",
        help="Non-debuggable benchmark build of the same .debug app, using its existing local identity",
    )
    parser.add_argument(
        "--keystore",
        type=Path,
        help="Existing private keystore, kept outside the checkout",
    )
    parser.add_argument(
        "--previous-apk",
        type=Path,
        help="Require the same public signing certificate as this APK",
    )
    args = parser.parse_args()
    isolated = args.debug or args.non_debug_test
    if isolated and (args.keystore or args.previous_apk):
        parser.error("Isolated test builds cannot use release signing arguments")
    debug_key = None
    if isolated:
        try:
            debug_key = debug_keystore()
        except ValueError as error:
            parser.error(str(error))
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    java_value = os.environ.get("JAVA_HOME")
    if not sdk_value or not java_value:
        parser.error("Set ANDROID_HOME and JAVA_HOME (JDK 21)")
    sdk, java = Path(sdk_value), Path(java_value) / "bin"
    bt = sdk / "build-tools" / "36.0.0"
    exe = ".exe" if os.name == "nt" else ""

    def run(command, cwd=ROOT, capture=False):
        result = subprocess.run(
            [str(x) for x in command],
            cwd=cwd,
            check=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE if capture else None,
        )
        return result.stdout

    if debug_key is not None:
        # Validate readability, public debug password and alias before touching build outputs.
        # A missing, corrupt or locked existing identity is NEVER replaced.
        run(
            [
                java / ("keytool" + exe),
                "-list",
                "-keystore",
                debug_key,
                "-storepass",
                "android",
                "-alias",
                "androiddebugkey",
            ],
            capture=True,
        )
    manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    version = manifest.get(ANDROID + "versionName", "")
    if not valid_version(version):
        parser.error(
            "Manifest versionName must be numeric, optionally followed by -alpha.N, -beta.N or -rc.N"
        )
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    gradle = args.gradle
    if not gradle:
        parser.error("Install Gradle 8.9 and put it on PATH, or set GRADLE")
    variant = (
        "benchmark" if args.non_debug_test else "debug" if args.debug else "release"
    )
    run(
        [
            gradle,
            "-p",
            ROOT,
            ":render-tests:assemble" + variant.capitalize(),
            "--console=plain",
        ]
    )
    unsigned = (
        ROOT
        / "render-tests/build/outputs/apk"
        / variant
        / ("render-tests-" + variant + "-unsigned.apk")
    )
    if not unsigned.is_file():
        raise RuntimeError(f"Gradle unsigned APK missing: {unsigned}")
    aligned = output / ("MapNotes-" + version + "-unsigned.apk")
    run([bt / ("zipalign" + exe), "-f", "4", unsigned, aligned])
    signer = [java / ("java" + exe), "-jar", bt / "lib/apksigner.jar"]
    result_apk = aligned
    if isolated:
        # Standard public Android debug credentials, not publisher signing material.
        suffix = "-nondebug-test.apk" if args.non_debug_test else "-debug.apk"
        result_apk = output / ("MapNotes-" + version + suffix)
        run(
            [
                *signer,
                "sign",
                "--ks",
                debug_key,
                "--ks-key-alias",
                "androiddebugkey",
                "--ks-pass",
                "pass:android",
                "--key-pass",
                "pass:android",
                "--out",
                result_apk,
                aligned,
            ]
        )
        print(
            run(
                [*signer, "verify", "--verbose", "--print-certs", result_apk],
                capture=True,
            )
        )
    elif args.keystore:
        if not args.keystore.is_file():
            parser.error(
                "The specified existing keystore is missing; it will NOT be regenerated"
            )
        if "MAPNOTES_STORE_PASSWORD" not in os.environ:
            parser.error("Set MAPNOTES_STORE_PASSWORD securely in the environment")
        result_apk = output / ("MapNotes-" + version + ".apk")
        run(
            [
                *signer,
                "sign",
                "--ks",
                args.keystore.resolve(),
                "--ks-pass",
                "env:MAPNOTES_STORE_PASSWORD",
                "--out",
                result_apk,
                aligned,
            ]
        )
        verified = run(
            [*signer, "verify", "--verbose", "--print-certs", result_apk], capture=True
        )
        print(verified)
        if args.previous_apk:
            previous = run(
                [*signer, "verify", "--print-certs", args.previous_apk.resolve()],
                capture=True,
            )
            pattern = r"Signer #1 certificate SHA-256 digest: (\S+)"
            old_cert, new_cert = re.search(pattern, previous), re.search(
                pattern, verified
            )
            if not old_cert or not new_cert or old_cert.group(1) != new_cert.group(1):
                raise RuntimeError(
                    "Signing certificate mismatch; do not distribute as an update"
                )
            print("UPDATE CERTIFICATE MATCH")
    elif args.previous_apk:
        parser.error("--previous-apk requires --keystore")
    run([bt / ("zipalign" + exe), "-c", "4", result_apk])
    print(run([bt / ("aapt2" + exe), "dump", "badging", result_apk], capture=True))
    digest = hashlib.sha256(result_apk.read_bytes()).hexdigest()
    print(digest + "  " + result_apk.name)
    print(
        "Isolated test APK verified: .debug app, persistent local certificate; not an official release update."
        if isolated
        else (
            "Unsigned APKs cannot be installed. Use an independently owned signing key for local builds."
            if not args.keystore
            else "Signed APK verified."
        )
    )


if __name__ == "__main__":
    build()
