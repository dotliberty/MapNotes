# Code formatting

Readable authored sources are a release requirement beginning with MapNotes 1.2.4. Do not minify application code, tests or build scripts. Keep generated APKs/classes, dependency caches, images and geographic data out of source-formatting passes.

## Standard and scope

- Java: google-java-format **1.24.0**, **AOSP / 4-space** indentation. Imports remain semantically unchanged (no unused-import removal or import reordering); long string literals are not rewritten.
- Kotlin: ktfmt **0.53**, `--kotlinlang-style` (4-space). Provide `--kotlin-formatter /outside/formatter/ktfmt-0.53-jar-with-dependencies.jar`. The helper checks SHA256 `76d05c614fe1864788557170fe89410f75134866a9f84011f9bd570ca12526b1` before execution.
- Python: Black **26.10.1**, default 88-column style and AST-equivalence validation.
- Android XML: four-space element indentation and multiline attribute lists. Resource string content, whitespace inside values and inline markup must be preserved.
- Gradle: four-space blocks, one statement per line. Authored Groovy files have a structural whitespace check (including string continuations); no general-purpose Groovy syntax rewrite is attempted.
- `.editorconfig`: UTF-8, LF, final newline and indentation defaults. `.gitattributes` keeps authored source checkouts on LF even with Windows `core.autocrlf` enabled.

The helper discovers tracked and nonignored authored Java/Kotlin/Python/XML/Gradle files through Git, including root Gradle configuration; it prints actual counts on every run. It does not scan build directories, caches, credentials or all files on the host. `assets/world.json` is geographic data, not minified application code, and is intentionally untouched.

## Commands

Provide the all-dependencies JAR from the official google/google-java-format **v1.24.0** release and the pinned Black installation outside the repository. The formatter never installs or downloads dependencies itself. Set `JAVA_HOME` to the existing JDK 21 as for the normal build.

```sh
# Optional isolated formatter dependency installation outside the checkout:
python -m pip install --target /outside/formatter/black-lib black==26.10.1

# Format:
python tools/format_code.py --java-formatter /outside/formatter/google-java-format-1.24.0-all-deps.jar --black-path /outside/formatter/black-lib --kotlin-formatter /outside/formatter/ktfmt-0.53-jar-with-dependencies.jar

# Read-only gate (exit 1 if formatting is needed):
python tools/format_code.py --check --java-formatter /outside/formatter/google-java-format-1.24.0-all-deps.jar --black-path /outside/formatter/black-lib --kotlin-formatter /outside/formatter/ktfmt-0.53-jar-with-dependencies.jar

python tools/test_formatting.py
python tools/verify_resources.py
python tools/test_pure.py
python tools/test.py
```

If Black is already available to the selected Python interpreter, omit `--black-path`. Use native drive paths on Windows. Generated files and third-party dependencies are not inputs to these commands. The project source remains independent of formatter packages at runtime.

## 1.2.4 formatting-stage verification

The equivalence and 251-test figures below describe the formatting stage before the subsequent camera-safety correction. That correction intentionally changes camera behavior; see `1.2.4-CAMERA-SAFETY.md` for the latest 259-test run and APK verification. The current source-formatting check remains clean.

- `--check` passed after formatting (a second initial Java pass was needed for one formatter normalization; final check is clean).
- Four formatter regression tests passed: resource value/mixed-text preservation, XML comments/namespaces/idempotence, readable Gradle validation and multiline resource references.
- Resource audit remains **419 EN/RU pairs / 239 direct references**. Two audit regexes in `verify_resources.py` and `test_localization.py` were explicitly extended to recognize line breaks around Java qualifiers/calls; otherwise formatting would silently reduce audit coverage. This is an isolated verification-tool correction, not an application behavior change.
- A pre-format baseline covered 96 existing source/config files. Java tokens were compared ignoring comments/whitespace and equivalent modifier order (`synchronized public` became `public synchronized` in WorldMap); Python ASTs were compared with the two declared audit-regex corrections accounted for; parsed XML values and Gradle tokens were preserved.
- Standalone SDK recompilation succeeded. `javap -c -p -s -constants` output for **128 application/generated classes** is exactly equal before and after formatting. This compares instructions, signatures and constants, not line/debug metadata, whole class files or APK bytes.
- Full Android/JVM suite: **251 tests, 248 passed, 3 external opt-in skipped, zero failures/errors**; `build/1.2.4-formatted-tests.log`. Pure checks, metadata and debug signing checks also passed.
- Version remains **1.2.4 / code 37**. The rebuilt debug APK has the existing separate `.debug` identity and persistent local debug signature; zipalign, CRC and outbox SHA-256 readback passed. APK integrity is recorded in `1.2.4-DESIGN.md`.

No claim of physical-device acceptance or byte-identical APKs is made. This formatting work does not publish a release, commit or push changes. The owner's main-agent AGENTS.md separately records the readability requirement for all future projects.
