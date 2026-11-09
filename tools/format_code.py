"""Format authored Java/Python/XML sources without touching builds or data.

Requires google-java-format 1.24.0 (AOSP) and Black 26.10.1.
Pass --check for a read-only release gate; dependencies are never downloaded here.
"""

from pathlib import Path
import argparse
import importlib
import hashlib
import os
import re
import subprocess
import sys
from xml.dom import Node, minidom
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
JAVA_VERSION = "1.24.0"
BLACK_VERSION = "26.10.1"
CODE_SUFFIXES = {".java", ".kt", ".py", ".xml", ".gradle"}
KTFMT_SHA256 = "76d05c614fe1864788557170fe89410f75134866a9f84011f9bd570ca12526b1"


def source_files():
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return sorted(
        {
            ROOT / name
            for name in result.stdout.decode("utf-8").split("\0")
            if name
            and Path(name).suffix in CODE_SUFFIXES
            and (ROOT / name).is_file()
            and (
                name in {"AndroidManifest.xml", "build.gradle", "settings.gradle"}
                or name.split("/")[0]
                in {"src", "tests", "tools", "res", "render-tests"}
            )
        }
    )


def xml_text(text):
    """Indent element-only XML; keep resource values and mixed text untouched."""
    document = minidom.parseString(text)

    def render(node, level):
        pad = "    " * level
        if node.nodeType != Node.ELEMENT_NODE:
            return pad + node.toxml()
        children = [
            child
            for child in node.childNodes
            if not (child.nodeType == Node.TEXT_NODE and not child.data.strip())
        ]
        # Android string contents (including inline markup) are not code whitespace.
        if node.tagName in {"string", "item"} or any(
            child.nodeType in {Node.TEXT_NODE, Node.CDATA_SECTION_NODE}
            and child.data.strip()
            for child in children
        ):
            return pad + node.toxml()
        attributes = [
            f"{name}={quoteattr(node.getAttribute(name))}"
            for name in node.attributes.keys()
        ]
        suffix = ">" if children else " />"
        single = "<" + node.tagName + (" " if attributes else "")
        single += " ".join(attributes) + suffix
        if len(pad + single) <= 100 and len(attributes) <= 2:
            opening = pad + single
        else:
            opening = pad + "<" + node.tagName
            opening += "\n" + "\n".join(
                pad + "    " + attribute for attribute in attributes
            )
            opening += suffix
        if not children:
            return opening
        return (
            opening
            + "\n"
            + "\n".join(render(child, level + 1) for child in children)
            + "\n"
            + pad
            + "</"
            + node.tagName
            + ">"
        )

    nodes = [
        node
        for node in document.childNodes
        if node.nodeType != Node.TEXT_NODE or node.data.strip()
    ]
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        + "\n".join(render(node, 0) for node in nodes)
        + "\n"
    )


def gradle_text(text):
    """Validate the small authored Gradle files; never rewrite Groovy syntax."""
    depth = 0
    continuation = False
    lines = text.splitlines()
    for number, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        expected = (
            depth - (1 if stripped.startswith("}") else 0) + (1 if continuation else 0)
        )
        if line[: len(line) - len(line.lstrip())] != "    " * expected:
            raise ValueError(f"Gradle indentation on line {number}")
        if stripped.startswith("//"):
            continue
        code = re.sub(r"'[^']*'|\"[^\"]*\"", "", stripped)
        if ";" in code:
            raise ValueError(f"Put each Gradle statement on its own line: {number}")
        depth += code.count("{") - code.count("}")
        continuation = stripped.endswith(("+", ","))
        if depth < 0:
            raise ValueError(f"Unbalanced Gradle block on line {number}")
    if depth:
        raise ValueError("Unbalanced Gradle blocks")
    return "\n".join(line.rstrip() for line in lines).rstrip() + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--java-formatter", type=Path, required=True)
    parser.add_argument("--black-path", type=Path)
    parser.add_argument("--kotlin-formatter", type=Path)
    args = parser.parse_args()
    if args.black_path:
        sys.path.insert(0, str(args.black_path))
    black = importlib.import_module("black")

    if black.__version__ != BLACK_VERSION:
        parser.error(f"Black {BLACK_VERSION} is required")
    home = os.environ.get("JAVA_HOME")
    java = (
        str(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        if home
        else "java"
    )
    jar = args.java_formatter.resolve()
    version = subprocess.run(
        [java, "-jar", str(jar), "--version"],
        check=True,
        capture_output=True,
        text=True,
    )
    if JAVA_VERSION not in version.stdout + version.stderr:
        parser.error(f"google-java-format {JAVA_VERSION} is required")
    files = source_files()
    java_files = [p for p in files if p.suffix == ".java"]
    flags = [
        "--aosp",
        "--skip-removing-unused-imports",
        "--skip-sorting-imports",
        "--skip-reflowing-long-strings",
    ]
    flags += ["--dry-run", "--set-exit-if-changed"] if args.check else ["--replace"]
    result = subprocess.run(
        [java, "-jar", str(jar), *flags, *map(str, java_files)], cwd=ROOT
    )
    if result.returncode not in {0, 1}:
        raise SystemExit(result.returncode)
    changed = result.returncode != 0
    kotlin_files = [p for p in files if p.suffix == ".kt"]
    if kotlin_files:
        formatter = args.kotlin_formatter
        if formatter is None or not formatter.is_file():
            parser.error("Pass --kotlin-formatter with the pinned ktfmt 0.53 JAR")
        if hashlib.sha256(formatter.read_bytes()).hexdigest() != KTFMT_SHA256:
            parser.error("ktfmt 0.53 SHA256 mismatch")
        kotlin_flags = ["--kotlinlang-style"]
        if args.check:
            kotlin_flags += ["--dry-run", "--set-exit-if-changed"]
        kotlin_result = subprocess.run(
            [
                java,
                "-jar",
                str(formatter.resolve()),
                *kotlin_flags,
                *map(str, kotlin_files),
            ],
            cwd=ROOT,
        )
        if kotlin_result.returncode not in {0, 1}:
            raise SystemExit(kotlin_result.returncode)
        changed |= kotlin_result.returncode != 0
    counts = {
        "java": len(java_files),
        "kotlin": len(kotlin_files),
        "python": 0,
        "xml": 0,
        "gradle": 0,
    }
    for path in files:
        suffix = path.suffix
        if suffix in {".java", ".kt"}:
            continue
        before = path.read_text(encoding="utf-8-sig")
        if suffix == ".py":
            counts["python"] += 1
            try:
                after = black.format_file_contents(
                    before, fast=False, mode=black.FileMode()
                )
            except black.NothingChanged:
                after = before
        elif suffix == ".xml":
            counts["xml"] += 1
            after = xml_text(before)
        else:
            counts["gradle"] += 1
            after = gradle_text(before)
        after = after.replace("\r\n", "\n")
        if path.read_bytes() == after.encode("utf-8"):
            continue
        if args.check:
            print(f"Needs formatting: {path.relative_to(ROOT)}")
            changed = True
        else:
            path.write_text(after, encoding="utf-8", newline="\n")
    print(f"Source scope: {counts}; mode={'check' if args.check else 'format'}")
    if changed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
