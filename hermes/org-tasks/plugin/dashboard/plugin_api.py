"""Authenticated Hermes REST routes for the org-tasks Desktop plugin."""

from __future__ import annotations

import importlib.util
import os
import sys
import threading
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field


_BACKEND_MODULE_NAME = "hermes_dashboard_plugin_org_tasks_backend"
_BACKEND_PATH = Path(__file__).with_name("backend.py")


def _load_backend_module():
    existing = sys.modules.get(_BACKEND_MODULE_NAME)
    if existing is not None:
        return existing
    spec = importlib.util.spec_from_file_location(_BACKEND_MODULE_NAME, _BACKEND_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load the org-tasks backend module.")
    module = importlib.util.module_from_spec(spec)
    sys.modules[_BACKEND_MODULE_NAME] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(_BACKEND_MODULE_NAME, None)
        raise
    return module


backend_module = _load_backend_module()
BackendError = backend_module.BackendError
BackendConfig = backend_module.BackendConfig
OrgTasksBackend = backend_module.OrgTasksBackend

router = APIRouter()
_backend = None
_backend_lock = threading.Lock()


def _configured_values() -> dict[str, Any]:
    try:
        from hermes_cli.config import load_config
    except ImportError as exc:
        raise BackendError("invalid-config", "Hermes configuration support is unavailable.", status_code=503) from exc
    try:
        config = load_config() or {}
    except Exception as exc:
        raise BackendError("invalid-config", "Hermes configuration could not be loaded.", status_code=503) from exc
    dashboard = config.get("dashboard") if isinstance(config, dict) else {}
    settings = dashboard.get("org_tasks") if isinstance(dashboard, dict) else {}
    return settings if isinstance(settings, dict) else {}


def _root_values(settings: dict[str, Any], key: str, env_key: str) -> list[str]:
    env_value = os.environ.get(env_key)
    if env_value:
        return [value for value in env_value.split(os.pathsep) if value]
    configured = settings.get(key, [])
    if isinstance(configured, dict):
        numeric_items = []
        for index, value in configured.items():
            if not isinstance(index, str) or not index.isdigit() or not isinstance(value, str):
                raise BackendError(
                    "invalid-config",
                    f"dashboard.org_tasks.{key} must contain numeric path entries.",
                    status_code=503,
                )
            numeric_items.append((int(index), value))
        return [value for _, value in sorted(numeric_items)]
    if not isinstance(configured, list) or not all(isinstance(value, str) for value in configured):
        raise BackendError(
            "invalid-config",
            f"dashboard.org_tasks.{key} must be a list or numeric map of paths.",
            status_code=503,
        )
    return configured


def _build_backend():
    settings = _configured_values()
    configured_ot = os.environ.get("HERMES_ORG_TASKS_OT") or settings.get("ot_path")
    configured_bb = os.environ.get("HERMES_ORG_TASKS_BB") or settings.get("bb_path")
    ot_path = Path(configured_ot).expanduser() if isinstance(configured_ot, str) and configured_ot else Path.home() / ".local/bin/ot"
    bb_path = Path(configured_bb).expanduser() if isinstance(configured_bb, str) and configured_bb else None
    read_roots = _root_values(settings, "read_roots", "HERMES_ORG_TASKS_READ_ROOTS")
    write_roots = _root_values(settings, "write_roots", "HERMES_ORG_TASKS_WRITE_ROOTS")
    read_root_paths = tuple(Path(value).expanduser() for value in read_roots)
    write_root_paths = tuple(Path(value).expanduser() for value in write_roots)
    read_resolved = {path.resolve() for path in read_root_paths}
    write_resolved = {path.resolve() for path in write_root_paths}
    if not write_resolved.issubset(read_resolved):
        raise BackendError(
            "invalid-config",
            "Every dashboard.org_tasks.write_roots entry must also appear in read_roots.",
            status_code=503,
        )
    return OrgTasksBackend(
        BackendConfig(
            ot_path=ot_path,
            bb_path=bb_path,
            read_roots=read_root_paths,
            write_roots=write_root_paths,
        )
    )


def _get_backend():
    global _backend
    if _backend is None:
        with _backend_lock:
            if _backend is None:
                _backend = _build_backend()
    return _backend


def _call(operation: Callable[[], dict]) -> dict:
    try:
        return operation()
    except BackendError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={"code": exc.code, "message": exc.message},
        ) from exc


class WorkspaceBody(BaseModel):
    cwd: str = Field(min_length=1, max_length=4096)


class SelectionBody(WorkspaceBody):
    task_id: str = Field(alias="taskId", min_length=36, max_length=36)


@router.get("/selected")
def selected(cwd: str = Query(min_length=1, max_length=4096)):
    return _call(lambda: _get_backend().selected(cwd))


@router.get("/tree")
def tree(cwd: str = Query(min_length=1, max_length=4096)):
    return _call(lambda: _get_backend().tree(cwd))


@router.post("/selection")
def select_task(payload: SelectionBody):
    return _call(lambda: _get_backend().select(payload.cwd, payload.task_id))


@router.post("/selection/clear")
def clear_selection(payload: WorkspaceBody):
    return _call(lambda: _get_backend().clear_selection(payload.cwd))
