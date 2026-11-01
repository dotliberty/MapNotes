"""Offline resource and source audit; no network or Android SDK required."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TOKEN = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(<]*)(\d*)(?:\.(\d+))?([a-zA-Z%])')

def resources(folder):
    entries = ET.parse(ROOT / 'res' / folder / 'strings.xml').getroot().findall('string')
    names = [e.attrib['name'] for e in entries]
    if len(names) != len(set(names)):
        raise AssertionError('Duplicate resource IDs')
    return {e.attrib['name']: ''.join(e.itertext()).strip('"') for e in entries}

def signature(value):
    result, position = [], 0
    for match in TOKEN.finditer(value):
        if match[5] == '%':
            continue
        position += 1
        result.append((int(match[1] or position), match[2], match[3], match[4], match[5]))
    return sorted(result, key=str)

class LocalizationResources(unittest.TestCase):
    def test_exhaustive_parity_and_format_signatures(self):
        en, ru = resources('values'), resources('values-ru')
        self.assertGreater(len(en), 300)
        self.assertEqual(en.keys(), ru.keys())
        for key in en:
            with self.subTest(key=key):
                self.assertTrue(en[key])
                self.assertTrue(ru[key])
                self.assertEqual(signature(en[key]), signature(ru[key]))
                if key != 'language_ru':
                    self.assertIsNone(re.search('[А-Яа-яЁё]', en[key]))

    def test_all_source_resource_references_exist(self):
        en = resources('values')
        checked = 0
        for source in (ROOT / 'src').rglob('*.java'):
            text = source.read_text(encoding='utf-8')
            for key in re.findall(r'R\.string\.(\w+)|Messages\.(?:text|format)\("([^"]+)"', text):
                self.assertIn(key[0] or key[1], en, str(source))
                checked += 1
            # App-owned Russian UI must live in resources, not Java string literals.
            for literal in re.findall(r'"(?:\\.|[^"\\])*"', text):
                self.assertIsNone(re.search('[А-Яа-яЁё]', literal), str(source) + ': ' + literal)
        self.assertGreater(checked, 250)

if __name__ == '__main__':
    unittest.main()
