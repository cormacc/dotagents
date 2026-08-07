from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient


PLUGIN_API_PATH = Path(__file__).resolve().parents[1] / "plugin" / "dashboard" / "plugin_api.py"


def load_plugin_api():
    if not PLUGIN_API_PATH.exists():
        return None
    module_name = "org_tasks_plugin_api_under_test"
    spec = importlib.util.spec_from_file_location(module_name, PLUGIN_API_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


class FakeBackend:
    def __init__(self):
        self.calls = []

    def selected(self, cwd):
        self.calls.append(("selected", cwd))
        return {"selectedId": None, "selected": None, "root": cwd, "writable": False}

    def tree(self, cwd):
        self.calls.append(("tree", cwd))
        return {"selectedId": None, "tree": [], "rows": [], "root": cwd, "writable": False}

    def select(self, cwd, task_id):
        self.calls.append(("select", cwd, task_id))
        return {"selectedId": task_id, "previousId": None, "root": cwd, "writable": True}

    def clear_selection(self, cwd):
        self.calls.append(("clear", cwd))
        return {"selectedId": None, "previousId": None, "root": cwd, "writable": True}


class PluginApiTests(unittest.TestCase):
    def test_accepts_numeric_root_maps_created_by_hermes_config_set(self):
        plugin_api = load_plugin_api()
        self.assertIsNotNone(plugin_api)

        roots = plugin_api._root_values(
            {"read_roots": {"1": "/workspace/two", "0": "/workspace/one"}},
            "read_roots",
            "ORG_TASKS_TEST_UNUSED",
        )

        self.assertEqual(roots, ["/workspace/one", "/workspace/two"])

    def test_exposes_selected_tree_select_and_clear_routes(self):
        plugin_api = load_plugin_api()
        self.assertIsNotNone(plugin_api, "Hermes org-tasks plugin API has not been implemented")

        fake = FakeBackend()
        plugin_api._backend = fake
        app = FastAPI()
        app.include_router(plugin_api.router)
        client = TestClient(app)
        cwd = "/workspace/project"
        task_id = "12345678-1234-4234-8234-123456789abc"

        self.assertEqual(client.get("/selected", params={"cwd": cwd}).status_code, 200)
        self.assertEqual(client.get("/tree", params={"cwd": cwd}).status_code, 200)
        selected = client.post("/selection", json={"cwd": cwd, "taskId": task_id})
        cleared = client.post("/selection/clear", json={"cwd": cwd})

        self.assertEqual(selected.status_code, 200)
        self.assertEqual(selected.json()["selectedId"], task_id)
        self.assertEqual(cleared.status_code, 200)
        self.assertEqual(
            fake.calls,
            [
                ("selected", cwd),
                ("tree", cwd),
                ("select", cwd, task_id),
                ("clear", cwd),
            ],
        )


if __name__ == "__main__":
    unittest.main()
