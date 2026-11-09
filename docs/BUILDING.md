# Build and test

## Prerequisites

- JDK 21 (the release workflow uses Android Studio JBR).
- Android SDK platform **35** and build-tools **36.0.0**.
- Python 3.10+ (`python` below).
- Gradle **8.9** for the test harness. The harness pins Android Gradle Plugin 8.5.2, Robolectric 4.16.1 and JUnit 4.13.2. A wrapper binary is not bundled: install Gradle explicitly.
- Network access for initial Gradle/Maven dependencies and Robolectric Android jars.

Set `JAVA_HOME` to the JDK directory and `ANDROID_HOME` to the Android SDK. Put Gradle 8.9 on `PATH`, or set `GRADLE` to its executable. On Windows use native paths when calling native executables. No Android Studio project import is required for the standalone APK builder.

## APK

From the repository root:

```sh
python tools/build.py
```

This performs javac (Java 8 bytecode), jar, D8, aapt2 resource compilation/linking, classes.dex packaging and zipalign. The result follows the manifest version, currently `build/MapNotes-1.2.4-unsigned.apk`. **Unsigned APKs are not installable.** Generated files and local signing material are not source files.

To sign a local build, provide an existing keystore that you own outside the checkout and securely set the `MAPNOTES_STORE_PASSWORD` environment variable. Do not paste passwords into shell history, issue reports or commits. The script passes the password to apksigner by environment reference, not as a command-line value:

```sh
python tools/build.py --keystore /secure/location/your-key.jks
```

For a publisher release, use `--keystore` together with `--previous-apk` through your protected local credential workflow. This release was checked against the published v1.0.0 certificate; see [1.2.0 release verification](1.2-RELEASE.md). `--debug` instead builds a separate `.debug` app with an existing persistent user-local debug keystore and is never a release-signing substitute.

An optional `--previous-apk /path/to/previous.apk` verifies that the new and previous APKs have the same public signing-certificate digest. The builder never creates or replaces a key. Keystores requiring a separate private-key password are not handled by this helper: use the SDK signing tools with your own secure credential workflow.

**The publisher's private signing key is not included.** Your own key produces an independent install and cannot update the official APK while preserving the same Android installation. Do not uninstall the official app merely to test your build if it contains data you need.

Builds are repeatable from source with the listed toolchain, but byte-for-byte reproducibility across hosts/tool versions is not claimed. Release asset integrity is established with the published SHA-256, not with a claim that an independently signed build has identical bytes.

## Android/JVM tests

```sh
python tools/verify_resources.py
python tools/test_pure.py
python tools/test.py
```

Equivalent command:

```sh
gradle -p render-tests testDebugUnitTest --rerun-tasks --console=plain
```

The Python wrapper deletes stale test-result XML before execution, rejects a failed Gradle process and requires nonempty passing test reports. A targeted run is possible with `--test ru.main.explorer.LanguageTest`.

Robolectric executes **API 34** with a test-harness target SDK of **34**. The distributable APK targets **35**. These tests exercise real JVM code, SQLite and Android/Canvas behavior under Robolectric; they do **not** demonstrate real Camera2 hardware capture, GPS reception, battery behavior or Android 15 foreground/background policy acceptance.

Public-network probes are opt-in (`MAPNOTES_LIVE_PROBE`, `MAPNOTES_CAPTURED_RESPONSE`); leave both unset for the normal deterministic regression suite. Skipped opt-in probes are not passing live-network checks. A supplied captured response must be public or synthetic data, never a private user's route.

## Before distributing a changed build

Verify package/versionCode, signing-certificate continuity, apksigner verification, zip alignment, test reports and asset checksums. Test an in-place update with a disposable database/device before relying on a build for irreplaceable data. Never publish local keys, logs, app databases, owner coordinates, unreviewed screenshots or an entire development workspace.

Formatting is also a release gate: follow [the code-formatting standard](FORMATTING.md), run `tools/format_code.py --check` with the pinned formatter dependencies, and run `python tools/test_formatting.py`. Keep authored sources readable; do not commit minified Java, tests or build scripts. Generated artifacts, dependency caches and geographic data are outside the source-formatting scope.
