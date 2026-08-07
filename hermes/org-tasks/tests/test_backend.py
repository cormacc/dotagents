from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any


BACKEND_PATH = Path(__file__).resolve().parents[1] / "plugin" / "dashboard" / "backend.py"


def load_backend_module():
    if not BACKEND_PATH.exists():
        return None
    spec = importlib.util.spec_from_file_location("org_tasks_backend_under_test", BACKEND_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_fake_ot(path: Path, argv_log: Path, envelope: dict) -> None:
    path.write_text(
        "#!/usr/bin/env python3\n"
        "import json, sys\n"
        f"open({str(argv_log)!r}, 'w', encoding='utf-8').write(json.dumps(sys.argv[1:]))\n"
        f"print(json.dumps({envelope!r}))\n",
        encoding="utf-8",
    )
    os.chmod(path, 0o755)


class RootAuthorizationTests(unittest.TestCase):
    def test_rejects_project_outside_read_allowlist_before_running_ot(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module, "org-tasks backend has not been implemented")

        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            allowed = base / "allowed"
            denied = base / "denied"
            allowed.mkdir()
            denied.mkdir()
            (allowed / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            (denied / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")

            config = backend_module.BackendConfig(
                ot_path=base / "never-run-ot",
                read_roots=(allowed,),
                write_roots=(),
            )
            backend = backend_module.OrgTasksBackend(config)

            with self.assertRaises(backend_module.BackendError) as caught:
                backend.selected(denied)

            self.assertEqual(caught.exception.code, "root-not-allowed")


class BackendConfigurationTests(unittest.TestCase):
    def test_rejects_relative_allowlist_roots(self):
        backend_module: Any = load_backend_module()

        with self.assertRaises(backend_module.BackendError) as caught:
            backend_module.BackendConfig(
                ot_path=Path("/absolute/ot"),
                read_roots=[Path("relative/project")],
                write_roots=[],
            )

        self.assertEqual(caught.exception.code, "invalid-config")


class SelectedTaskTests(unittest.TestCase):
    def test_uses_configured_babashka_directory_for_wrapper_dependencies(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        task_id = "abababab-abab-4bab-8bab-abababababab"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tasks_path = root / "TASKS.org"
            tasks_path.write_text("* Tasks\n", encoding="utf-8")
            runtime_dir = root / "runtime"
            runtime_dir.mkdir()
            fake_bb = runtime_dir / "bb"
            envelope = {
                "ok": True,
                "schema": "org-tasks/v1",
                "result": {
                    "task": {
                        "id": task_id,
                        "status": "TODO",
                        "summary": "Runtime task",
                        "sourcePath": str(tasks_path),
                    },
                    "ancestors": [],
                },
                "warnings": [],
            }
            fake_bb.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' {json.dumps(envelope)!r}\n",
                encoding="utf-8",
            )
            os.chmod(fake_bb, 0o755)
            wrapper = root / "ot-wrapper"
            wrapper.write_text("#!/bin/sh\nexec bb \"$@\"\n", encoding="utf-8")
            os.chmod(wrapper, 0o755)
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=wrapper,
                    bb_path=fake_bb,
                    read_roots=(root,),
                    write_roots=(),
                )
            )

            result = backend.selected(root)

            self.assertEqual(result["selectedId"], task_id)

    def test_runs_fixed_selected_command_and_sanitizes_the_result(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        task_id = "12345678-1234-4234-8234-123456789abc"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            argv_log = root / "argv.json"
            envelope = {
                "ok": True,
                "schema": "org-tasks/v1",
                "result": {
                    "task": {
                        "id": task_id,
                        "status": "STARTED",
                        "priority": "A",
                        "summary": "Selected task",
                        "description": "Continue implementation.",
                        "tags": ["desktop"],
                        "level": 2,
                        "sourcePath": str(root / "TASKS.org"),
                        "importPath": "design/log/task.org",
                        "importExpandedPath": str(root / "design/log/task.org"),
                        "propertyLines": [":SECRET_INTERNAL: not-for-ui"],
                        "sourceContent": "raw org content",
                        "children": [],
                        "importChildren": [],
                    },
                    "ancestors": [],
                    "record": None,
                },
                "warnings": [],
            }
            fake_ot = root / "fake-ot"
            write_fake_ot(fake_ot, argv_log, envelope)

            config = backend_module.BackendConfig(
                ot_path=fake_ot,
                read_roots=(root,),
                write_roots=(),
            )
            result = backend_module.OrgTasksBackend(config).selected(root)

            self.assertEqual(
                json.loads(argv_log.read_text(encoding="utf-8")),
                ["--root", str(root.resolve()), "--format", "json", "--no-color", "selected"],
            )
            self.assertEqual(result["selectedId"], task_id)
            self.assertEqual(result["selected"]["summary"], "Selected task")
            self.assertNotIn("sourcePath", result["selected"])
            self.assertNotIn("importPath", result["selected"])
            self.assertNotIn("importExpandedPath", result["selected"])
            self.assertNotIn("propertyLines", result["selected"])
            self.assertNotIn("sourceContent", result["selected"])
            self.assertEqual(result["root"], str(root.resolve()))
            self.assertFalse(result["writable"])

    def test_rejects_source_paths_outside_the_project_root(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        task_id = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "project"
            root.mkdir()
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            argv_log = root / "argv.json"
            fake_ot = root / "fake-ot"
            write_fake_ot(
                fake_ot,
                argv_log,
                {
                    "ok": True,
                    "schema": "org-tasks/v1",
                    "result": {
                        "task": {
                            "id": task_id,
                            "status": "TODO",
                            "summary": "Escaped",
                            "sourcePath": str(root.parent / "outside.org"),
                        },
                        "ancestors": [],
                    },
                    "warnings": [],
                },
            )
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=(root,),
                    write_roots=(),
                )
            )

            with self.assertRaises(backend_module.BackendError) as caught:
                backend.selected(root)

            self.assertEqual(caught.exception.code, "ot-path-escape")


class TaskTreeTests(unittest.TestCase):
    def test_returns_sanitized_tree_without_raw_source_payloads(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        parent_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        child_id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tasks_path = root / "TASKS.org"
            tasks_path.write_text("* Tasks\n", encoding="utf-8")
            argv_log = root / "argv.json"
            child = {
                "id": child_id,
                "status": "TODO",
                "summary": "Child",
                "level": 3,
                "parentId": parent_id,
                "sourcePath": str(tasks_path),
                "children": [],
                "importChildren": [],
                "effectiveSourceContent": "must not escape",
            }
            parent = {
                "id": parent_id,
                "status": "STARTED",
                "summary": "Parent",
                "level": 2,
                "parentId": None,
                "sourcePath": str(tasks_path),
                "children": [child],
                "importChildren": [],
            }
            envelope = {
                "ok": True,
                "schema": "org-tasks/v1",
                "result": {
                    "tree": [parent],
                    "rows": [parent, child],
                    "selectedId": child_id,
                    "sources": {str(tasks_path): {"sourceContent": "raw"}},
                },
                "warnings": [{"code": "example", "message": "A safe warning", "location": str(tasks_path)}],
            }
            fake_ot = root / "fake-ot"
            write_fake_ot(fake_ot, argv_log, envelope)
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            result = backend.tree(root)

            self.assertEqual(json.loads(argv_log.read_text(encoding="utf-8"))[-1], "list")
            self.assertEqual(result["selectedId"], child_id)
            self.assertTrue(result["writable"])
            self.assertEqual(result["tree"][0]["children"][0]["id"], child_id)
            self.assertNotIn("effectiveSourceContent", result["tree"][0]["children"][0])
            self.assertNotIn("sources", result)
            self.assertEqual(result["warnings"], [{"code": "example", "message": "A safe warning"}])


class ProcessBoundaryTests(unittest.TestCase):
    module: Any

    @classmethod
    def setUpClass(cls):
        cls.module = load_backend_module()

    def test_times_out_and_terminates_the_ot_process_group(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            project = temp / "project"
            project.mkdir()
            (project / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            fake_ot = temp / "ot"
            fake_ot.write_text("#!/bin/sh\nsleep 5\n", encoding="utf-8")
            fake_ot.chmod(0o755)
            backend = self.module.OrgTasksBackend(
                self.module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=[project],
                    write_roots=[],
                    timeout_seconds=0.05,
                )
            )

            started = time.monotonic()
            with self.assertRaises(self.module.BackendError) as caught:
                backend.selected(project)

            self.assertEqual(caught.exception.code, "ot-timeout")
            self.assertLess(time.monotonic() - started, 2.0)

    def test_rejects_output_above_the_configured_cap(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            project = temp / "project"
            project.mkdir()
            (project / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            fake_ot = temp / "ot"
            fake_ot.write_text("#!/bin/sh\nprintf '%080d' 0\nsleep 5\n", encoding="utf-8")
            fake_ot.chmod(0o755)
            backend = self.module.OrgTasksBackend(
                self.module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=[project],
                    write_roots=[],
                    timeout_seconds=2,
                    max_output_bytes=32,
                )
            )

            started = time.monotonic()
            with self.assertRaises(self.module.BackendError) as caught:
                backend.selected(project)

            self.assertEqual(caught.exception.code, "ot-output-too-large")
            self.assertLess(time.monotonic() - started, 1.0)

    def test_rejects_immediate_oversized_exit(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            project = temp / "project"
            project.mkdir()
            (project / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            fake_ot = temp / "ot"
            fake_ot.write_text("#!/bin/sh\nprintf '%080d' 0\n", encoding="utf-8")
            fake_ot.chmod(0o755)
            backend = self.module.OrgTasksBackend(
                self.module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=[project],
                    write_roots=[],
                    timeout_seconds=2,
                    max_output_bytes=32,
                )
            )

            with self.assertRaises(self.module.BackendError) as caught:
                backend.selected(project)

            self.assertEqual(caught.exception.code, "ot-output-too-large")

    @unittest.skipIf(os.name == "nt", "POSIX process-group behavior")
    def test_sweeps_descendant_after_successful_leader_exit(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            project = temp / "project"
            project.mkdir()
            tasks_path = project / "TASKS.org"
            tasks_path.write_text("* Tasks\n", encoding="utf-8")
            survived = temp / "descendant-survived"
            task_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            envelope = {
                "ok": True,
                "schema": "org-tasks/v1",
                "result": {
                    "task": {
                        "id": task_id,
                        "status": "TODO",
                        "summary": "Selected task",
                        "sourcePath": str(tasks_path),
                    },
                    "ancestors": [],
                },
                "warnings": [],
            }
            fake_ot = temp / "ot"
            fake_ot.write_text(
                "#!/bin/sh\n"
                f"(trap '' TERM; sleep 0.4; printf survived > {str(survived)!r}; sleep 5) &\n"
                f"printf '%s\\n' {json.dumps(envelope)!r}\n",
                encoding="utf-8",
            )
            fake_ot.chmod(0o755)
            backend = self.module.OrgTasksBackend(
                self.module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=[project],
                    write_roots=[],
                    timeout_seconds=2,
                )
            )

            result = backend.selected(project)
            time.sleep(0.6)

            self.assertEqual(result["selected"]["id"], task_id)
            self.assertFalse(survived.exists())

    def test_preserves_structured_ot_conflicts_without_exposing_stderr(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            project = temp / "project"
            project.mkdir()
            (project / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            fake_ot = temp / "ot"
            envelope = {
                "ok": False,
                "schema": "org-tasks/v1",
                "result": None,
                "warnings": [],
                "error": {"code": "conflict", "message": "Task state changed; refresh and retry."},
            }
            fake_ot.write_text(
                "#!/usr/bin/env python3\n"
                "import json, sys\n"
                f"print(json.dumps({envelope!r}))\n"
                "print('secret diagnostic', file=sys.stderr)\n"
                "raise SystemExit(2)\n",
                encoding="utf-8",
            )
            fake_ot.chmod(0o755)
            backend = self.module.OrgTasksBackend(
                self.module.BackendConfig(ot_path=fake_ot, read_roots=[project], write_roots=[])
            )

            with self.assertRaises(self.module.BackendError) as caught:
                backend.selected(project)

            self.assertEqual(caught.exception.code, "conflict")
            self.assertEqual(caught.exception.status_code, 409)
            self.assertEqual(caught.exception.message, "Task state changed; refresh and retry.")
            self.assertNotIn("secret", caught.exception.message)


class SelectionMutationTests(unittest.TestCase):
    def test_rejects_local_selection_file_symlink_outside_project_root(self):
        backend_module: Any = load_backend_module()
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            root = temp / "project"
            root.mkdir()
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            outside = temp / "outside.org"
            outside.write_text("#+SELECTED:\n", encoding="utf-8")
            (root / "TASKS.local.org").symlink_to(outside)
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=root / "never-run-ot",
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            with self.assertRaises(backend_module.BackendError) as caught:
                backend.select(root, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

            self.assertEqual(caught.exception.code, "selection-path-escape")

    def test_rejects_non_uuid_selection_before_running_ot(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=root / "never-run-ot",
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            with self.assertRaises(backend_module.BackendError) as caught:
                backend.select(root, "abcd")

            self.assertEqual(caught.exception.code, "invalid-task-id")

    def test_rejects_noncanonical_uppercase_uuid_before_running_ot(self):
        backend_module: Any = load_backend_module()
        self.assertIsNotNone(backend_module)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=root / "never-run-ot",
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            with self.assertRaises(backend_module.BackendError) as caught:
                backend.select(root, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")

            self.assertEqual(caught.exception.code, "invalid-task-id")

    def test_selects_full_uuid_in_a_write_authorized_root(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        task_id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        previous_id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            argv_log = root / "argv.json"
            fake_ot = root / "fake-ot"
            write_fake_ot(
                fake_ot,
                argv_log,
                {
                    "ok": True,
                    "schema": "org-tasks/v1",
                    "result": {
                        "selectedId": task_id,
                        "previousId": previous_id,
                        "file": str(root / "TASKS.local.org"),
                    },
                    "warnings": [],
                },
            )
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            result = backend.select(root, task_id)

            self.assertEqual(
                json.loads(argv_log.read_text(encoding="utf-8"))[-2:],
                ["select", task_id],
            )
            self.assertEqual(result["selectedId"], task_id)
            self.assertEqual(result["previousId"], previous_id)
            self.assertTrue(result["writable"])
            self.assertNotIn("file", result)

    def test_clears_selection_only_in_a_write_authorized_root(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        previous_id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")
            argv_log = root / "argv.json"
            fake_ot = root / "fake-ot"
            write_fake_ot(
                fake_ot,
                argv_log,
                {
                    "ok": True,
                    "schema": "org-tasks/v1",
                    "result": {
                        "selectedId": None,
                        "previousId": previous_id,
                        "file": str(root / "TASKS.local.org"),
                    },
                    "warnings": [],
                },
            )
            backend = backend_module.OrgTasksBackend(
                backend_module.BackendConfig(
                    ot_path=fake_ot,
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )

            result = backend.clear_selection(root)

            self.assertEqual(
                json.loads(argv_log.read_text(encoding="utf-8"))[-2:],
                ["select", "--clear"],
            )
            self.assertIsNone(result["selectedId"])
            self.assertEqual(result["previousId"], previous_id)

    def test_serializes_selection_mutations_for_the_same_project_root(self):
        backend_module = load_backend_module()
        self.assertIsNotNone(backend_module)

        first_id = "11111111-1111-4111-8111-111111111111"
        second_id = "22222222-2222-4222-8222-222222222222"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TASKS.org").write_text("* Tasks\n", encoding="utf-8")

            class TrackingBackend(backend_module.OrgTasksBackend):
                def __init__(self, config):
                    super().__init__(config)
                    self.active = 0
                    self.max_active = 0
                    self.guard = threading.Lock()

                def _run_ot(self, project_root, *arguments):
                    with self.guard:
                        self.active += 1
                        self.max_active = max(self.max_active, self.active)
                    time.sleep(0.05)
                    with self.guard:
                        self.active -= 1
                    return {"selectedId": arguments[-1], "previousId": None}, []

            backend = TrackingBackend(
                backend_module.BackendConfig(
                    ot_path=root / "unused",
                    read_roots=(root,),
                    write_roots=(root,),
                )
            )
            errors = []

            def select(task_id):
                try:
                    backend.select(root, task_id)
                except Exception as exc:  # pragma: no cover - assertion reports the concrete failures
                    errors.append(exc)

            threads = [
                threading.Thread(target=select, args=(first_id,)),
                threading.Thread(target=select, args=(second_id,)),
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

            self.assertEqual(errors, [])
            self.assertEqual(backend.max_active, 1)


if __name__ == "__main__":
    unittest.main()
