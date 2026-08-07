"""Safety boundary between Hermes Desktop and the org-tasks CLI."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Iterable
from uuid import UUID


SCHEMA = "org-tasks/v1"
TASK_FIELDS = (
    "id",
    "status",
    "priority",
    "summary",
    "description",
    "tags",
    "level",
    "local",
    "selected",
    "closed",
    "started",
    "createdAt",
    "blockedBy",
    "handoff",
    "linkedIssues",
    "parentId",
)


class BackendError(Exception):
    """A stable backend failure suitable for conversion to an HTTP error."""

    def __init__(self, code: str, message: str, *, status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class BackendConfig:
    def __init__(
        self,
        *,
        ot_path: Path,
        bb_path: Path | None = None,
        read_roots: Iterable[Path],
        write_roots: Iterable[Path],
        timeout_seconds: float = 10.0,
        max_output_bytes: int = 2_000_000,
    ) -> None:
        self.ot_path = Path(ot_path).expanduser()
        self.bb_path = Path(bb_path).expanduser() if bb_path is not None else None
        self.read_roots = self._canonical_roots(read_roots, "read_roots")
        self.write_roots = self._canonical_roots(write_roots, "write_roots")
        self.timeout_seconds = timeout_seconds
        self.max_output_bytes = max_output_bytes

    @staticmethod
    def _canonical_roots(roots: Iterable[Path], label: str) -> tuple[Path, ...]:
        canonical = []
        for value in roots:
            path = Path(value).expanduser()
            if not path.is_absolute():
                raise BackendError(
                    "invalid-config",
                    f"Every configured {label} entry must be a non-empty absolute path.",
                    status_code=503,
                )
            canonical.append(path.resolve())
        return tuple(canonical)


class OrgTasksBackend:
    def __init__(self, config: BackendConfig) -> None:
        self.config = config
        self._mutation_locks: dict[Path, threading.Lock] = {}
        self._mutation_locks_guard = threading.Lock()

    def selected(self, cwd: Path | str) -> dict:
        root = self._resolve_root(cwd, write=False)
        result, warnings = self._run_ot(root, "selected")
        selected = result.get("task")
        ancestors = result.get("ancestors")
        if ancestors is None:
            ancestors = []
        elif not isinstance(ancestors, list) or not all(isinstance(task, dict) for task in ancestors):
            raise BackendError("invalid-ot-result", "The org-tasks selected result is invalid.", status_code=502)
        selected_id = selected.get("id") if isinstance(selected, dict) else result.get("selectedId")
        return {
            "root": str(root),
            "writable": root in self.config.write_roots,
            "selectedId": selected_id,
            "selected": self._sanitize_task(selected, root) if isinstance(selected, dict) else None,
            "ancestors": [
                self._sanitize_task(task, root)
                for task in ancestors
            ],
            "warnings": self._sanitize_warnings(warnings),
        }

    def tree(self, cwd: Path | str) -> dict:
        root = self._resolve_root(cwd, write=False)
        result, warnings = self._run_ot(root, "list")
        tree = result.get("tree")
        rows = result.get("rows")
        if not isinstance(tree, list) or not isinstance(rows, list):
            raise BackendError("invalid-ot-result", "The org-tasks list result is invalid.", status_code=502)
        if not all(isinstance(task, dict) for task in tree) or not all(isinstance(task, dict) for task in rows):
            raise BackendError("invalid-ot-result", "The org-tasks list contains invalid tasks.", status_code=502)
        return {
            "root": str(root),
            "writable": root in self.config.write_roots,
            "selectedId": result.get("selectedId"),
            "tree": [self._sanitize_task(task, root) for task in tree],
            "rows": [self._sanitize_task(task, root) for task in rows],
            "warnings": self._sanitize_warnings(warnings),
        }

    def select(self, cwd: Path | str, task_id: str) -> dict:
        try:
            canonical_id = str(UUID(task_id))
        except (ValueError, AttributeError, TypeError) as exc:
            raise BackendError("invalid-task-id", "Selection requires a full task UUID.") from exc
        if canonical_id != task_id:
            raise BackendError("invalid-task-id", "Selection requires a canonical full task UUID.")
        root = self._resolve_root(cwd, write=True)
        self._validate_selection_target(root)
        with self._mutation_lock(root):
            result, warnings = self._run_ot(root, "select", canonical_id)
        if result.get("selectedId") != canonical_id:
            raise BackendError("invalid-ot-result", "The org-tasks selection result is invalid.", status_code=502)
        return {
            "root": str(root),
            "writable": True,
            "selectedId": canonical_id,
            "previousId": result.get("previousId"),
            "warnings": self._sanitize_warnings(warnings),
        }

    def clear_selection(self, cwd: Path | str) -> dict:
        root = self._resolve_root(cwd, write=True)
        self._validate_selection_target(root)
        with self._mutation_lock(root):
            result, warnings = self._run_ot(root, "select", "--clear")
        if result.get("selectedId") is not None:
            raise BackendError("invalid-ot-result", "The org-tasks clear result is invalid.", status_code=502)
        return {
            "root": str(root),
            "writable": True,
            "selectedId": None,
            "previousId": result.get("previousId"),
            "warnings": self._sanitize_warnings(warnings),
        }

    def _mutation_lock(self, root: Path) -> threading.Lock:
        with self._mutation_locks_guard:
            return self._mutation_locks.setdefault(root, threading.Lock())

    @staticmethod
    def _validate_selection_target(root: Path) -> None:
        local_path = root / "TASKS.local.org"
        try:
            resolved = local_path.resolve(strict=False)
            resolved.relative_to(root)
        except (OSError, RuntimeError, ValueError) as exc:
            raise BackendError(
                "selection-path-escape",
                "TASKS.local.org resolves outside its project root.",
                status_code=403,
            ) from exc
        if local_path.exists() and not resolved.is_file():
            raise BackendError(
                "invalid-selection-file",
                "TASKS.local.org is not a regular file.",
                status_code=409,
            )

    def _run_ot(self, root: Path, *arguments: str) -> tuple[dict[str, Any], list[Any]]:
        ot_path = self._resolve_ot_path()
        bb_path = self._resolve_dependency_path(self.config.bb_path, "bb") if self.config.bb_path else None
        argv = [
            str(ot_path),
            "--root",
            str(root),
            "--format",
            "json",
            "--no-color",
            *arguments,
        ]
        runtime_dirs = [str(path.parent) for path in (bb_path, ot_path) if path is not None]
        runtime_dirs.extend(["/usr/bin", "/bin", "/usr/sbin", "/sbin"])
        environment = {
            "HOME": os.environ.get("HOME", str(Path.home())),
            "LANG": os.environ.get("LANG", "C.UTF-8"),
            "TERM": "dumb",
            "NO_COLOR": "1",
            "CLICOLOR": "0",
            "PATH": os.pathsep.join(dict.fromkeys(runtime_dirs)),
        }
        for key in ("NIX_SSL_CERT_FILE", "SSL_CERT_FILE", "TMPDIR", "XDG_CACHE_HOME"):
            if value := os.environ.get(key):
                environment[key] = value
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            popen_kwargs: dict[str, Any] = {
                "stdin": subprocess.DEVNULL,
                "stdout": stdout,
                "stderr": stderr,
                "env": environment,
                "cwd": str(root),
            }
            if os.name == "nt":
                popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                popen_kwargs["start_new_session"] = True
            try:
                process = subprocess.Popen(argv, **popen_kwargs)
            except OSError as exc:
                raise BackendError(
                    "ot-launch-failed",
                    "The configured org-tasks command could not be started.",
                    status_code=503,
                ) from exc
            deadline = time.monotonic() + self.config.timeout_seconds
            while True:
                stdout_size = os.fstat(stdout.fileno()).st_size
                stderr_size = os.fstat(stderr.fileno()).st_size
                if stdout_size > self.config.max_output_bytes or stderr_size > self.config.max_output_bytes:
                    self._terminate_process(process)
                    raise BackendError(
                        "ot-output-too-large",
                        "The org-tasks command produced too much output.",
                        status_code=502,
                    )
                if process.poll() is not None:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._terminate_process(process)
                    raise BackendError("ot-timeout", "The org-tasks command timed out.", status_code=504)
                time.sleep(min(0.05, remaining))

            # A launcher may exit after forking a descendant that inherited the
            # output files. Sweep the process group even on successful leader
            # exit, then re-check the files so a final write cannot race the cap.
            self._terminate_process(process)
            stdout_size = os.fstat(stdout.fileno()).st_size
            stderr_size = os.fstat(stderr.fileno()).st_size
            if stdout_size > self.config.max_output_bytes or stderr_size > self.config.max_output_bytes:
                raise BackendError(
                    "ot-output-too-large",
                    "The org-tasks command produced too much output.",
                    status_code=502,
                )

            stdout.seek(0)
            stderr.seek(0)
            stdout_text = stdout.read().decode("utf-8", errors="replace")
            stderr_text = stderr.read().decode("utf-8", errors="replace")

        if process.returncode != 0 and not stdout_text.strip():
            raise BackendError(
                "ot-failed",
                "The org-tasks command failed without a JSON response; verify its configured runtime dependencies.",
                status_code=503,
            )
        try:
            envelope = json.loads(stdout_text)
        except json.JSONDecodeError as exc:
            raise BackendError("invalid-ot-output", "The org-tasks command returned invalid JSON.", status_code=502) from exc
        if not isinstance(envelope, dict) or envelope.get("schema") != SCHEMA:
            raise BackendError("invalid-ot-schema", "The org-tasks command returned an unsupported schema.", status_code=502)
        if process.returncode != 0 or envelope.get("ok") is not True:
            error = envelope.get("error") if isinstance(envelope.get("error"), dict) else {}
            code = error.get("code") if isinstance(error.get("code"), str) else "ot-failed"
            message = error.get("message") if isinstance(error.get("message"), str) else "The org-tasks command failed."
            if not error and stderr_text.strip():
                message = "The org-tasks command failed; consult the Hermes error log."
            raise BackendError(code, message, status_code=409 if code == "conflict" else 400)
        result = envelope.get("result")
        if not isinstance(result, dict):
            raise BackendError("invalid-ot-result", "The org-tasks command returned an invalid result.", status_code=502)
        warnings = envelope.get("warnings")
        return result, warnings if isinstance(warnings, list) else []

    def _resolve_ot_path(self) -> Path:
        if not self.config.ot_path.is_absolute():
            raise BackendError("ot-not-configured", "The configured ot path must be absolute.", status_code=503)
        try:
            resolved = self.config.ot_path.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise BackendError("ot-not-found", "The configured ot executable does not exist.", status_code=503) from exc
        if not resolved.is_file() or not os.access(resolved, os.X_OK):
            raise BackendError("ot-not-executable", "The configured ot path is not executable.", status_code=503)
        return resolved

    @staticmethod
    def _resolve_dependency_path(path: Path, name: str) -> Path:
        if not path.is_absolute():
            raise BackendError("invalid-config", f"The configured {name} path must be absolute.", status_code=503)
        try:
            resolved = path.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise BackendError(f"{name}-not-found", f"The configured {name} executable does not exist.", status_code=503) from exc
        if not resolved.is_file() or not os.access(resolved, os.X_OK):
            raise BackendError(f"{name}-not-executable", f"The configured {name} path is not executable.", status_code=503)
        return resolved

    @staticmethod
    def _terminate_process(process: subprocess.Popen) -> None:
        if os.name == "nt":
            system_root = os.environ.get("SystemRoot", r"C:\Windows")
            taskkill = Path(system_root) / "System32" / "taskkill.exe"
            try:
                subprocess.run(
                    [str(taskkill), "/PID", str(process.pid), "/T", "/F"],
                    check=False,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=2,
                )
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                process.kill()
            return

        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        except PermissionError:
            # On macOS the output file can become visible during the narrow
            # pre-exec window before start_new_session has made the child a
            # signalable group leader. Kill and reap that leader directly; it
            # cannot have launched descendants before exec completes.
            try:
                process.kill()
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                process.kill()
            return
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
        # The leader may exit while a descendant in the same group ignores
        # SIGTERM. Always sweep the group with SIGKILL after the grace period.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()

    def _sanitize_task(self, task: dict[str, Any], root: Path) -> dict[str, Any]:
        task_id = task.get("id")
        try:
            canonical_id = str(UUID(task_id))
        except (ValueError, AttributeError, TypeError) as exc:
            raise BackendError("invalid-ot-result", "The org-tasks result contains an invalid task.", status_code=502) from exc
        if canonical_id != task_id or not isinstance(task.get("status"), str) or not isinstance(task.get("summary"), str):
            raise BackendError("invalid-ot-result", "The org-tasks result contains an invalid task.", status_code=502)
        for path_field in ("sourcePath", "importPath", "importExpandedPath"):
            if isinstance(value := task.get(path_field), str):
                self._require_contained_path(value, root)
        sanitized = {key: task.get(key) for key in TASK_FIELDS if key in task}
        for child_field in ("children", "importChildren"):
            children = task.get(child_field)
            if children is not None and (
                not isinstance(children, list) or not all(isinstance(child, dict) for child in children)
            ):
                raise BackendError("invalid-ot-result", "The org-tasks result contains invalid child tasks.", status_code=502)
            sanitized[child_field] = [
                self._sanitize_task(child, root)
                for child in children
            ] if isinstance(children, list) else []
        return sanitized

    @staticmethod
    def _sanitize_warnings(warnings: list[Any]) -> list[dict[str, str]]:
        return [
            {
                key: value
                for key in ("code", "message")
                if isinstance((value := warning.get(key)), str)
            }
            for warning in warnings
            if isinstance(warning, dict)
        ]

    @staticmethod
    def _require_contained_path(value: str, root: Path) -> Path:
        try:
            unresolved = Path(value).expanduser()
            candidate = (unresolved if unresolved.is_absolute() else root / unresolved).resolve(strict=False)
            candidate.relative_to(root)
        except (OSError, RuntimeError, ValueError) as exc:
            raise BackendError("ot-path-escape", "The org-tasks result contains a path outside its project root.", status_code=502) from exc
        return candidate

    def _resolve_root(self, cwd: Path | str, *, write: bool) -> Path:
        requested = Path(cwd).expanduser()
        try:
            current = requested.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise BackendError("invalid-cwd", "The workspace directory does not exist.") from exc
        if not current.is_dir():
            current = current.parent

        project_root = None
        for candidate in (current, *current.parents):
            tasks_file = candidate / "TASKS.org"
            if tasks_file.is_file():
                try:
                    resolved_tasks = tasks_file.resolve(strict=True)
                except (OSError, RuntimeError) as exc:
                    raise BackendError("invalid-task-file", "TASKS.org cannot be resolved.") from exc
                if resolved_tasks.parent != candidate:
                    raise BackendError("root-escape", "TASKS.org resolves outside its project root.")
                project_root = candidate
                break

        if project_root is None:
            raise BackendError("tasks-not-found", "No TASKS.org was found for this workspace.", status_code=404)

        allowed = self.config.write_roots if write else self.config.read_roots
        if project_root not in allowed:
            raise BackendError(
                "root-not-allowed",
                "The task project is not in the configured root allowlist.",
                status_code=403,
            )
        return project_root
