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
        "--debug",
        action="store_true",
        help="Separate .debug application using existing ~/.android/debug.keystore; never creates keys or reads release credentials",
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
    if args.debug and (args.keystore or args.previous_apk):
        parser.error("--debug cannot use release signing arguments")
    debug_key = None
    if args.debug:
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
    work = output / "sdk-build"
    if work.exists():
        shutil.rmtree(work)
    classes, dex = work / "classes", work / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    android = sdk / "platforms/android-35/android.jar"
    generated = work / "generated"
    generated.mkdir()
    resources = work / "resources.zip"
    unsigned = work / "unsigned.apk"
    manifest_path = ROOT / "AndroidManifest.xml"
    if args.debug:
        package = manifest.get("package")
        application = manifest.find("application")
        if not package or application is None:
            parser.error("Manifest must contain package and application")
        for component in manifest.findall("./application/*"):
            name = component.get(ANDROID + "name", "")
            if name.startswith("."):
                component.set(ANDROID + "name", package + name)
        manifest.set("package", package + ".debug")
        application.set(ANDROID + "debuggable", "true")
        application.set(ANDROID + "label", "MapNotes beta DEBUG")
        manifest_path = work / "AndroidManifest.xml"
        ET.register_namespace("android", ANDROID[1:-1])
        ET.ElementTree(manifest).write(
            manifest_path, encoding="utf-8", xml_declaration=True
        )
    run([bt / ("aapt2" + exe), "compile", "--dir", ROOT / "res", "-o", resources])
    run(
        [
            bt / ("aapt2" + exe),
            "link",
            "-o",
            unsigned,
            "--manifest",
            manifest_path,
            "-I",
            android,
            "-A",
            ROOT / "assets",
            "--java",
            generated,
            "--custom-package",
            "ru.main.explorer",
            resources,
        ]
    )
    run(
        [
            java / ("javac" + exe),
            "-encoding",
            "UTF-8",
            "--release",
            "8",
            "-classpath",
            android,
            "-d",
            classes,
            *sorted((ROOT / "src").rglob("*.java")),
            *sorted(generated.rglob("*.java")),
        ]
    )
    jar = work / "classes.jar"
    run([java / ("jar" + exe), "cf", jar, "-C", classes, "."])
    run(
        [
            java / ("java" + exe),
            "-cp",
            bt / "lib/d8.jar",
            "com.android.tools.r8.D8",
            "--lib",
            android,
            "--min-api",
            "26",
            "--output",
            dex,
            jar,
        ]
    )

    run([bt / ("aapt" + exe), "add", unsigned, "classes.dex"], cwd=dex)
    aligned = output / ("MapNotes-" + version + "-unsigned.apk")
    run([bt / ("zipalign" + exe), "-f", "4", unsigned, aligned])
    signer = [java / ("java" + exe), "-jar", bt / "lib/apksigner.jar"]
    result_apk = aligned
    if args.debug:
        # Standard public Android debug credentials, not publisher signing material.
        result_apk = output / ("MapNotes-" + version + "-debug.apk")
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
        "Debug APK verified: separate .debug app, persistent local debug certificate; not an official release update."
        if args.debug
        else (
            "Unsigned APKs cannot be installed. Use an independently owned signing key for local builds."
            if not args.keystore
            else "Signed APK verified."
        )
    )


if __name__ == "__main__":
    build()
