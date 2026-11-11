# Build and test

## Prerequisites

- JDK 21 (the release workflow uses Android Studio JBR).
- Android SDK platform **35** and build-tools **36.0.0**.
- Python 3.10+ (`python` below).
- Gradle **8.9** for the shared application/test build. Pins: Android Gradle Plugin 8.5.2, Kotlin 1.9.23, Google Play services Location 21.3.0, Robolectric 4.16.1 and JUnit 4.13.2. A wrapper binary is not bundled: install Gradle explicitly.
- Network access for initial Gradle/Maven dependencies and Robolectric Android jars.

Set `JAVA_HOME` to the JDK directory and `ANDROID_HOME` to the Android SDK. Put Gradle 8.9 on `PATH`, or set `GRADLE` to its executable. On Windows use native paths when calling native executables. No Android Studio project import is required. Both the app and tests use the historical `render-tests` module from the root Gradle project; its name does not mean that APKs omit the app or Kotlin code.

## APK

From the repository root:

```sh
python tools/build.py
```

This invokes the common Gradle Java/Kotlin build, resolves pinned Android dependencies, packages all required dex/resources and aligns the unsigned APK. The result follows the manifest version, currently `build/MapNotes-1.3.2-unsigned.apk`. **Unsigned APKs are not installable.** No dependency binaries, generated manifests or local signing material are source files.

For the separate installable debug app, use `python tools/build.py --debug`, followed by `python tools/verify_debug_apk.py build/MapNotes-1.3.2-debug.apk`. The persistent user-local debug keystore must already exist; this workflow will not generate it. Gradle signing is disabled; the helper validates the existing key, then aligns/signs/verifies through the Android SDK tools.

For performance comparisons without ART debugging mode, use
`python tools/build.py --non-debug-test`. This builds the `benchmark` variant with
`debuggable=false` and minification disabled, retaining the `.debug` application ID
and the same existing local signing identity so it can update the test app rather
than replace the official app. Verify the resulting `MapNotes-<version>-nondebug-test.apk`
with `tools/verify_debug_apk.py <apk> --non-debuggable`. The signing identity and
package suffix do not make the APK debuggable. Run its JVM tests with
`python tools/test.py --variant benchmark`; this is not a physical-device FPS test.

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
gradle :render-tests:testDebugUnitTest --rerun-tasks --console=plain
```

The Python wrapper deletes stale test-result XML before execution, rejects a failed Gradle process and requires nonempty passing test reports. A targeted run is possible with `--test ru.main.explorer.LanguageTest`.

Robolectric tests explicitly select their simulated SDK (normally **API 34**). The shared manifest and APK target **35**. These tests exercise real Java/Kotlin code, SQLite and Android/Canvas behavior under Robolectric; they do **not** demonstrate real Camera2 capture, activity-recognition accuracy, GPS reception, battery behavior or Android 15 foreground/background policy acceptance.

Public-network probes are opt-in (`MAPNOTES_LIVE_PROBE`, `MAPNOTES_CAPTURED_RESPONSE`); leave both unset for the normal deterministic regression suite. Skipped opt-in probes are not passing live-network checks. A supplied captured response must be public or synthetic data, never a private user's route.

## Before distributing a changed build

Verify package/versionCode, signing-certificate continuity, apksigner verification, zip alignment, test reports and asset checksums. Test an in-place update with a disposable database/device before relying on a build for irreplaceable data. Never publish local keys, logs, app databases, owner coordinates, unreviewed screenshots or an entire development workspace.

Formatting is also a release gate: follow [the code-formatting standard](FORMATTING.md), run `tools/format_code.py --check` with the pinned formatter dependencies, and run `python tools/test_formatting.py`. Keep authored sources readable; do not commit minified Java, tests or build scripts. Generated artifacts, dependency caches and geographic data are outside the source-formatting scope.
