"""Regression checks for beta version names and split translation resources."""
import os
from pathlib import Path
import tempfile
import unittest
import importlib.util
import verify_resources
import verify_debug_apk

spec = importlib.util.spec_from_file_location('mapnotes_sdk_builder', Path(__file__).with_name('build.py'))
assert spec is not None and spec.loader is not None
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

class BuildMetadataTest(unittest.TestCase):
    def test_debug_verifier_reads_manifest_version_without_hardcode(self):
        self.assertEqual(('1.2.0-beta.2', 29), verify_debug_apk.expected_version(build.ROOT / 'AndroidManifest.xml'))
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            manifest = Path(temporary) / 'AndroidManifest.xml'
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="9.8.7-beta.6" android:versionCode="765"/>', encoding='utf-8')
            self.assertEqual(('9.8.7-beta.6', 765), verify_debug_apk.expected_version(manifest))
    def test_release_and_explicit_prerelease_versions(self):
        for version in ('1.0', '1.0.2', '1.1.0-beta.1', '2.0.0-alpha.2', '2.0.0-rc.12'):
            self.assertTrue(build.valid_version(version), version)
        for version in ('', '../1.0', '1.0.0/evil', '1.0.0-beta', '1.0.0-beta.0', '1.0.0-beta.1/evil', '1.0.0;evil', '1.0.0\n'):
            self.assertFalse(build.valid_version(version), repr(version))

    def test_all_resource_files_are_audited_and_duplicates_rejected(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            root = Path(temporary)
            (root/'strings.xml').write_text('<resources><string name="base">Base</string></resources>', encoding='utf-8')
            (root/'imagery_strings.xml').write_text('<resources><string name="beta">Beta</string></resources>', encoding='utf-8')
            self.assertEqual({'base': 'Base', 'beta': 'Beta'}, verify_resources.load_directory(root))
            (root/'duplicate.xml').write_text('<resources><string name="beta">Duplicate</string></resources>', encoding='utf-8')
            with self.assertRaises(AssertionError):
                verify_resources.load_directory(root)

if __name__ == '__main__':
    unittest.main()
