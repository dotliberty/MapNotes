"""Compile/run pure Java GPS and geometry tests, then localization audits."""
from pathlib import Path
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def main():
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        raise SystemExit('Set JAVA_HOME to JDK 21')
    java = Path(java_home) / 'bin'
    exe = '.exe' if os.name == 'nt' else ''
    classes = ROOT / 'build/pure-tests'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    names = ['GpsFilter', 'Visibility', 'RegionPlan']
    sources = [ROOT / ('src/ru/main/explorer/' + name + '.java') for name in names + ['Messages']]
    sources += sorted((ROOT / 'tests').glob('*.java'))
    subprocess.run([str(java / ('javac' + exe)), '-encoding', 'UTF-8', '--release', '8',
                    '-d', str(classes), *map(str, sources)], check=True)
    for name in names + ['ru.main.explorer.MessageFallback']:
        subprocess.run([str(java / ('java' + exe)), '-cp', str(classes), name + 'Test'], check=True)
    subprocess.run([sys.executable, str(ROOT / 'tests/test_localization.py')], check=True)

if __name__ == '__main__':
    main()
