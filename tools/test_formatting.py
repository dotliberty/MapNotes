"""Regression checks for whitespace-only formatting and multiline source audits."""

import re
import unittest
import xml.etree.ElementTree as ET

import format_code


class FormattingTests(unittest.TestCase):
    def test_xml_preserves_resource_spaces_and_inline_markup(self):
        text = (
            '<resources><string name="note">  A &amp; B '
            '<b>bold</b>  tail </string><item name="blank">   </item></resources>'
        )
        formatted = format_code.xml_text(text)
        before, after = ET.fromstring(text), ET.fromstring(formatted)
        self.assertEqual(
            ET.tostring(before[0]),
            ET.tostring(after[0]).rstrip(),
        )
        self.assertEqual(before[1].text, after[1].text)
        self.assertEqual(formatted, format_code.xml_text(formatted))

    def test_xml_attributes_comments_and_namespaces_are_stable(self):
        text = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="example" android:versionCode="37">'
            '<!-- keep --><application android:label="A &amp; B"/></manifest>'
        )
        formatted = format_code.xml_text(text)
        self.assertIn("<!-- keep -->", formatted)
        self.assertEqual(ET.fromstring(text).attrib, ET.fromstring(formatted).attrib)
        self.assertEqual(formatted, format_code.xml_text(formatted))

    def test_gradle_keeps_readable_blocks_and_rejects_minified_statements(self):
        text = "plugins {\n    id 'example'\n}\n"
        self.assertEqual(text, format_code.gradle_text(text))
        with self.assertRaises(ValueError):
            format_code.gradle_text("plugins { id 'one'; id 'two' }\n")
        with self.assertRaises(ValueError):
            format_code.gradle_text("plugins {\n id 'example'\n}\n")

    def test_resource_reference_audit_accepts_java_line_breaks(self):
        references = re.findall(
            r"(?<!android\.)R\s*\.\s*string\s*\.\s*(\w+)",
            "R.string.note + R.string\n    .camera + android.R.string.ok",
        )
        self.assertEqual(["note", "camera"], references)


if __name__ == "__main__":
    unittest.main()
