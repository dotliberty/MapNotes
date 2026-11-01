"""Check EN/RU resource parity and resource references without Android tooling."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def load(path):
    result = {}
    for node in ET.parse(path).getroot():
        if node.tag != 'string':
            continue
        name = node.attrib['name']
        if name in result:
            raise AssertionError('Duplicate resource: ' + name)
        result[name] = ''.join(node.itertext())
    return result

def formats(value):
    return sorted(re.findall(r'%(?!%)(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]', value))

def main():
    en = load(ROOT / 'res/values/strings.xml')
    ru = load(ROOT / 'res/values-ru/strings.xml')
    assert en and en.keys() == ru.keys(), 'EN/RU string keys differ'
    for name in en:
        assert en[name] and ru[name], 'Empty string: ' + name
        assert formats(en[name]) == formats(ru[name]), 'Format mismatch: ' + name
    references = set()
    for source in (ROOT / 'src').rglob('*.java'):
        content = source.read_text(encoding='utf-8')
        references.update(re.findall(r'(?<!android\.)R\.string\.(\w+)', content))
        literals = re.findall(r'"(?:[^"\\]|\\.)*"', content)
        assert not any(re.search('[А-Яа-яЁё]', text) for text in literals), 'Hardcoded Cyrillic app string: ' + source.name
    assert references <= en.keys(), 'Missing resource references: ' + ', '.join(sorted(references - en.keys()))
    print('RESOURCE AUDIT OK:', len(en), 'paired EN/RU strings;', len(references), 'direct resource references; no hardcoded Cyrillic app strings')

if __name__ == '__main__':
    main()
