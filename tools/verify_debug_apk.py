"""Verify an SDK-built isolated debug APK, optionally copy/read back to an outbox."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def expected_version(manifest_path):
    manifest = ET.parse(manifest_path).getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    return manifest.attrib[android + 'versionName'], int(manifest.attrib[android + 'versionCode'])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--outbox', type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve()
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    def run(command):
        return subprocess.run([str(x) for x in command], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
    badging = run([sdk / 'aapt2.exe', 'dump', 'badging', apk])
    assert "package: name='ru.main.explorer.debug'" in badging
    version, code = expected_version(ROOT / 'AndroidManifest.xml')
    assert f"versionName='{version}'" in badging and f"versionCode='{code}'" in badging
    assert "launchable-activity: name='ru.main.explorer.MainActivity'" in badging
    assert "targetSdkVersion:'35'" in badging and 'application-debuggable' in badging
    assert "application-label:'MapNotes beta DEBUG'" in badging
    certificate = run([java, '-jar', sdk / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk])
    assert 'Verifies' in certificate
    public_cert = subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/keytool.exe'),
                                 '-exportcert', '-keystore', str(Path.home() / '.android/debug.keystore'),
                                 '-storepass', 'android', '-alias', 'androiddebugkey'],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    signer_digest = re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', certificate)
    assert signer_digest and signer_digest.group(1).lower() == hashlib.sha256(public_cert).hexdigest()
    run([sdk / 'zipalign.exe', '-c', '4', apk])
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert {'classes.dex', 'AndroidManifest.xml', 'resources.arsc'} <= set(archive.namelist())
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    result = dict(apk=str(apk), bytes=apk.stat().st_size, sha256=digest,
                  package='ru.main.explorer.debug', version=version, versionCode=code,
                  targetSdk=35, signature='verified persistent local debug', zipalign='passed', zipCRC='passed')
    if args.outbox:
        args.outbox.mkdir(parents=True, exist_ok=True)
        target = args.outbox / apk.name
        shutil.copy2(apk, target)
        assert target.read_bytes() == apk.read_bytes()
        assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
        result['outbox'] = str(target.resolve())
        result['outboxReadback'] = 'SHA-256 match'
    report = ROOT / 'build/1.2-apk-verification.json'
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
