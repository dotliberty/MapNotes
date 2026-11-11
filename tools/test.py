"""Run Android/Robolectric tests and reject empty or failing reports."""

from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--gradle",
        default=os.environ.get("GRADLE") or shutil.which("gradle"),
        help="Gradle 8.9 executable (or set GRADLE)",
    )
    parser.add_argument("--test", help="Optional test class/method selector")
    parser.add_argument(
        "--variant", choices=("debug", "benchmark", "release"), default="debug"
    )
    args = parser.parse_args()
    if not args.gradle:
        parser.error("Install Gradle 8.9 and put it on PATH, or set GRADLE")
    task = "test" + args.variant.capitalize() + "UnitTest"
    reports = ROOT / "render-tests/build/test-results" / task
    if reports.exists():
        shutil.rmtree(reports)
    command = [
        args.gradle,
        "-p",
        str(ROOT),
        ":render-tests:" + task,
        "--rerun-tasks",
        "--console=plain",
    ]
    if args.test:
        command += ["--tests", args.test]
    subprocess.run(command, cwd=ROOT, check=True)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    files = sorted(reports.glob("TEST-*.xml"))
    for report in files:
        suite = ET.parse(report).getroot()
        for field in totals:
            totals[field] += int(suite.get(field, "0"))
    totals["passed"] = (
        totals["tests"] - totals["skipped"] - totals["errors"] - totals["failures"]
    )
    print(json.dumps(dict(suites=len(files), **totals)))
    if not files or not totals["passed"] or totals["failures"] or totals["errors"]:
        raise SystemExit("No passing reports, or test failures/errors found")
    print(
        "Robolectric/JVM checks only: not physical GPS, hardware camera or Android device acceptance."
    )


if __name__ == "__main__":
    main()
