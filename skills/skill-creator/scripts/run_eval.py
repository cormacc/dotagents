#!/usr/bin/env python3
"""Run trigger evaluation for a skill description.

Tests whether a skill's description causes the agent to trigger (read the
skill) for a set of queries. The agent is pi by default, or Claude Code with
--harness claude. Outputs results as JSON.
"""

import argparse
import json
import os
import select
import subprocess
import sys
import time
import uuid
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

from scripts.utils import parse_skill_md

HARNESSES = ("pi", "claude")

PI_SWAP_EXTENSION = Path(__file__).resolve().parent / "pi_eval_swap.ts"


def find_project_root(harness: str = "pi") -> Path:
    """Return the directory that eval runs use as their working directory.

    pi takes project context from the working directory and its ancestors, so
    the cwd is used as is. For Claude Code, walk up to the nearest .claude/,
    as Claude Code does, so that the command file we create ends up where
    claude -p looks for it.
    """
    current = Path.cwd()
    if harness == "pi":
        return current
    for parent in [current, *current.parents]:
        if (parent / ".claude").is_dir():
            return parent
    return current


def run_single_query(
    query: str,
    skill_name: str,
    skill_description: str,
    timeout: int,
    project_root: str,
    model: str | None = None,
    harness: str = "pi",
    skill_path: str | None = None,
) -> bool:
    """Run a single query and return whether the skill was triggered."""
    if harness == "pi":
        if skill_path is None:
            raise ValueError("the pi harness needs skill_path")
        return _run_single_query_pi(
            query, skill_name, skill_description, timeout, project_root,
            model, skill_path,
        )
    return _run_single_query_claude(
        query, skill_name, skill_description, timeout, project_root, model,
    )


def _run_single_query_pi(
    query: str,
    skill_name: str,
    skill_description: str,
    timeout: int,
    project_root: str,
    model: str | None,
    skill_path: str,
) -> bool:
    """Run one query through `pi --mode json` and report whether it read the skill.

    pi_eval_swap.ts puts the candidate description into the real prompt and
    reports the SKILL.md path that the prompt shows. The first assistant
    message decides: the skill triggered if any of its tool calls names that
    path. A run that cannot decide (no swap report, provider error, early
    exit, timeout) raises, so that run_eval reports it instead of counting it
    silently as "not triggered".
    """
    cmd = ["pi", "--mode", "json", "--no-session", "-e", str(PI_SWAP_EXTENSION)]
    if model:
        cmd.extend(["--model", model])
    cmd.append(query)

    env = {
        **os.environ,
        "SKILL_EVAL_NAME": skill_name,
        "SKILL_EVAL_DESCRIPTION": skill_description,
        "SKILL_EVAL_DRAFT_PATH": str(Path(skill_path).resolve() / "SKILL.md"),
    }

    process = subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        cwd=project_root,
        env=env,
    )

    start_time = time.time()
    buffer = b""
    tokens: set[str] = set()
    tool_args: dict[int, str] = {}

    try:
        while time.time() - start_time < timeout:
            ready, _, _ = select.select([process.stdout], [], [], 1.0)
            if not ready:
                if process.poll() is not None:
                    break
                continue

            chunk = os.read(process.stdout.fileno(), 8192)
            if not chunk:
                break
            buffer += chunk

            # pi frames records on LF only; split bytes before decoding.
            while b"\n" in buffer:
                raw, buffer = buffer.split(b"\n", 1)
                try:
                    event = json.loads(raw.decode("utf-8", errors="replace"))
                except json.JSONDecodeError:
                    continue

                event_type = event.get("type")
                if event_type == "entry_appended":
                    entry = event.get("entry", {})
                    if entry.get("customType") != "skill-eval-swap":
                        continue
                    data = entry.get("data", {})
                    if data.get("hidden"):
                        raise RuntimeError(
                            f"{skill_name} sets disable-model-invocation, so it cannot trigger"
                        )
                    shown = data["filePath"]
                    # The model can name the shown path or the file it links to.
                    tokens = {shown, os.path.realpath(shown)}

                elif event_type == "message_update":
                    ame = event.get("assistantMessageEvent", {})
                    if ame.get("type") == "toolcall_delta" and tokens:
                        index = ame.get("contentIndex", 0)
                        tool_args[index] = tool_args.get(index, "") + ame.get("delta", "")
                        if any(t in tool_args[index] for t in tokens):
                            return True

                elif event_type == "message_end":
                    message = event.get("message", {})
                    if message.get("role") != "assistant":
                        continue
                    if message.get("stopReason") in ("error", "aborted"):
                        raise RuntimeError(
                            f"pi run ended with {message.get('stopReason')}: "
                            f"{message.get('errorMessage', '')}"
                        )
                    if not tokens:
                        raise RuntimeError(
                            "pi_eval_swap.ts did not report a swap; the prompt "
                            "may not contain the candidate description"
                        )
                    calls = [c for c in message.get("content", []) if c.get("type") == "toolCall"]
                    return any(
                        t in json.dumps(c.get("arguments", {}), ensure_ascii=False) for c in calls for t in tokens
                    )
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()

    # No decision: a timeout or an early exit (e.g. an unknown model).
    if time.time() - start_time >= timeout:
        raise TimeoutError(f"pi gave no first assistant message within {timeout}s")
    raise RuntimeError(f"pi exited {process.returncode} before the first assistant message ended")


def _run_single_query_claude(
    query: str,
    skill_name: str,
    skill_description: str,
    timeout: int,
    project_root: str,
    model: str | None = None,
) -> bool:
    """Run a single query through `claude -p` and return whether it triggered.

    Creates a command file in .claude/commands/ so it appears in Claude's
    available_skills list, then runs `claude -p` with the raw query.
    Uses --include-partial-messages to detect triggering early from
    stream events (content_block_start) rather than waiting for the
    full assistant message, which only arrives after tool execution.
    """
    unique_id = uuid.uuid4().hex[:8]
    clean_name = f"{skill_name}-skill-{unique_id}"
    project_commands_dir = Path(project_root) / ".claude" / "commands"
    command_file = project_commands_dir / f"{clean_name}.md"

    try:
        project_commands_dir.mkdir(parents=True, exist_ok=True)
        # Use YAML block scalar to avoid breaking on quotes in description
        indented_desc = "\n  ".join(skill_description.split("\n"))
        command_content = (
            f"---\n"
            f"description: |\n"
            f"  {indented_desc}\n"
            f"---\n\n"
            f"# {skill_name}\n\n"
            f"This skill handles: {skill_description}\n"
        )
        command_file.write_text(command_content)

        cmd = [
            "claude",
            "-p", query,
            "--output-format", "stream-json",
            "--verbose",
            "--include-partial-messages",
        ]
        if model:
            cmd.extend(["--model", model])

        # Remove CLAUDECODE env var to allow nesting claude -p inside a
        # Claude Code session. The guard is for interactive terminal conflicts;
        # programmatic subprocess usage is safe.
        env = {k: v for k, v in os.environ.items() if k != "CLAUDECODE"}

        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            cwd=project_root,
            env=env,
        )

        triggered = False
        start_time = time.time()
        buffer = ""
        # Track state for stream event detection
        pending_tool_name = None
        accumulated_json = ""

        try:
            while time.time() - start_time < timeout:
                if process.poll() is not None:
                    remaining = process.stdout.read()
                    if remaining:
                        buffer += remaining.decode("utf-8", errors="replace")
                    break

                ready, _, _ = select.select([process.stdout], [], [], 1.0)
                if not ready:
                    continue

                chunk = os.read(process.stdout.fileno(), 8192)
                if not chunk:
                    break
                buffer += chunk.decode("utf-8", errors="replace")

                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue

                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    # Early detection via stream events
                    if event.get("type") == "stream_event":
                        se = event.get("event", {})
                        se_type = se.get("type", "")

                        if se_type == "content_block_start":
                            cb = se.get("content_block", {})
                            if cb.get("type") == "tool_use":
                                tool_name = cb.get("name", "")
                                if tool_name in ("Skill", "Read"):
                                    pending_tool_name = tool_name
                                    accumulated_json = ""
                                else:
                                    return False

                        elif se_type == "content_block_delta" and pending_tool_name:
                            delta = se.get("delta", {})
                            if delta.get("type") == "input_json_delta":
                                accumulated_json += delta.get("partial_json", "")
                                if clean_name in accumulated_json:
                                    return True

                        elif se_type in ("content_block_stop", "message_stop"):
                            if pending_tool_name:
                                return clean_name in accumulated_json
                            if se_type == "message_stop":
                                return False

                    # Fallback: full assistant message
                    elif event.get("type") == "assistant":
                        message = event.get("message", {})
                        for content_item in message.get("content", []):
                            if content_item.get("type") != "tool_use":
                                continue
                            tool_name = content_item.get("name", "")
                            tool_input = content_item.get("input", {})
                            if tool_name == "Skill" and clean_name in tool_input.get("skill", ""):
                                triggered = True
                            elif tool_name == "Read" and clean_name in tool_input.get("file_path", ""):
                                triggered = True
                            return triggered

                    elif event.get("type") == "result":
                        return triggered
        finally:
            # Clean up process on any exit path (return, exception, timeout)
            if process.poll() is None:
                process.kill()
                process.wait()

        return triggered
    finally:
        if command_file.exists():
            command_file.unlink()


def run_eval(
    eval_set: list[dict],
    skill_name: str,
    description: str,
    num_workers: int,
    timeout: int,
    project_root: Path,
    runs_per_query: int = 1,
    trigger_threshold: float = 0.5,
    model: str | None = None,
    harness: str = "pi",
    skill_path: Path | None = None,
) -> dict:
    """Run the full eval set and return results."""
    results = []

    with ProcessPoolExecutor(max_workers=num_workers) as executor:
        future_to_info = {}
        for item in eval_set:
            for run_idx in range(runs_per_query):
                future = executor.submit(
                    run_single_query,
                    item["query"],
                    skill_name,
                    description,
                    timeout,
                    str(project_root),
                    model,
                    harness,
                    str(skill_path) if skill_path else None,
                )
                future_to_info[future] = (item, run_idx)

        query_triggers: dict[str, list[bool]] = {}
        query_items: dict[str, dict] = {}
        for future in as_completed(future_to_info):
            item, _ = future_to_info[future]
            query = item["query"]
            query_items[query] = item
            if query not in query_triggers:
                query_triggers[query] = []
            try:
                query_triggers[query].append(future.result())
            except Exception as e:
                print(f"Warning: query failed: {e}", file=sys.stderr)
                query_triggers[query].append(False)

    for query, triggers in query_triggers.items():
        item = query_items[query]
        trigger_rate = sum(triggers) / len(triggers)
        should_trigger = item["should_trigger"]
        if should_trigger:
            did_pass = trigger_rate >= trigger_threshold
        else:
            did_pass = trigger_rate < trigger_threshold
        results.append({
            "query": query,
            "should_trigger": should_trigger,
            "trigger_rate": trigger_rate,
            "triggers": sum(triggers),
            "runs": len(triggers),
            "pass": did_pass,
        })

    passed = sum(1 for r in results if r["pass"])
    total = len(results)

    return {
        "skill_name": skill_name,
        "description": description,
        "results": results,
        "summary": {
            "total": total,
            "passed": passed,
            "failed": total - passed,
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Run trigger evaluation for a skill description")
    parser.add_argument("--eval-set", required=True, help="Path to eval set JSON file")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--description", default=None, help="Override description to test")
    parser.add_argument("--num-workers", type=int, default=10, help="Number of parallel workers")
    parser.add_argument("--timeout", type=int, default=30, help="Timeout per query in seconds")
    parser.add_argument("--runs-per-query", type=int, default=3, help="Number of runs per query")
    parser.add_argument("--trigger-threshold", type=float, default=0.5, help="Trigger rate threshold")
    parser.add_argument("--model", default=None, help="Model for the harness, in its own syntax (default: user's configured model)")
    add_harness_args(parser)
    parser.add_argument("--verbose", action="store_true", help="Print progress to stderr")
    args = parser.parse_args()

    eval_set = json.loads(Path(args.eval_set).read_text())
    skill_path = Path(args.skill_path)

    if not (skill_path / "SKILL.md").exists():
        print(f"Error: No SKILL.md found at {skill_path}", file=sys.stderr)
        sys.exit(1)

    name, original_description, content = parse_skill_md(skill_path)
    description = args.description or original_description
    project_root = project_root_from_args(args)

    if args.verbose:
        print(f"Evaluating: {description}", file=sys.stderr)

    output = run_eval(
        eval_set=eval_set,
        skill_name=name,
        description=description,
        num_workers=args.num_workers,
        timeout=args.timeout,
        project_root=project_root,
        runs_per_query=args.runs_per_query,
        trigger_threshold=args.trigger_threshold,
        model=args.model,
        harness=args.harness,
        skill_path=skill_path,
    )

    if args.verbose:
        summary = output["summary"]
        print(f"Results: {summary['passed']}/{summary['total']} passed", file=sys.stderr)
        for r in output["results"]:
            status = "PASS" if r["pass"] else "FAIL"
            rate_str = f"{r['triggers']}/{r['runs']}"
            print(f"  [{status}] rate={rate_str} expected={r['should_trigger']}: {r['query'][:70]}", file=sys.stderr)

    print(json.dumps(output, indent=2))


def add_harness_args(parser: argparse.ArgumentParser) -> None:
    """Add the options that select and configure the agent harness."""
    parser.add_argument("--harness", choices=HARNESSES, default="pi", help="Agent CLI that runs the queries (default: pi)")
    parser.add_argument(
        "--cwd", default=None, metavar="DIR",
        help="Working directory for eval runs: the project whose context the skill is used in "
             "(default: the current directory for pi, the nearest .claude/ ancestor for claude)",
    )


def project_root_from_args(args: argparse.Namespace) -> Path:
    """Resolve --cwd, or fall back to the harness default."""
    if args.cwd:
        return Path(args.cwd).expanduser().resolve()
    return find_project_root(args.harness)


if __name__ == "__main__":
    main()
