from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PluginLayoutTests(unittest.TestCase):
    def test_backend_metadata_is_hidden_api_only_org_tasks_plugin(self):
        plugin_yaml = ROOT / "plugin" / "plugin.yaml"
        init_py = ROOT / "plugin" / "__init__.py"
        manifest_path = ROOT / "plugin" / "dashboard" / "manifest.json"
        entry = ROOT / "plugin" / "dashboard" / "index.js"

        self.assertTrue(plugin_yaml.is_file())
        self.assertTrue(init_py.is_file())
        self.assertTrue(entry.is_file())
        self.assertIn("name: org-tasks", plugin_yaml.read_text(encoding="utf-8"))

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["name"], "org-tasks")
        self.assertEqual(manifest["api"], "plugin_api.py")
        self.assertTrue(manifest["tab"]["hidden"])
        self.assertEqual(manifest["entry"], "index.js")
        self.assertEqual(manifest["label"], "Org Tasks")


if __name__ == "__main__":
    unittest.main()
