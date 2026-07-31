#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Path-Scoped Spec Context Injection Hook (ticket-refresh model)

When the agent touches a file, the specs that govern that file are surfaced
right then — small, relevant, budgeted — instead of everything up front or
nothing at all. Spec .md files under .trellis/spec/ declare which code paths
they govern via YAML frontmatter (`paths:` glob list); the matching engine
lives in .trellis/scripts/common/spec_match.py and the decision engine in
.trellis/scripts/common/spec_inject.py. This file is the IO shell: stdin,
config, identity, state files, locking, GC, one print.

Triggers:

* Claude Code PostToolUse (matcher "Read|Edit|Write|MultiEdit") receives one
  structured file path after the tool runs.
* Codex PreToolUse (matcher "Edit|Write") receives an ``apply_patch`` command.
  Every patch header is matched before the patch runs. When a FULL spec is
  emitted, the patch is denied once so the model can read the injected rules
  and retry; ticket-only reminders do not block.
* OpenCode ``tool.execute.before`` adapts ``write``, ``edit``, and
  ``apply_patch`` into the same PreToolUse payload. FULL delivery uses the same
  deny-once decision before the JS plugin surfaces context as a tool error.

Behavior — per matched spec, per event (recency-decay aware):

    h    = sha256(spec bytes)
    last = newest emission recorded for (identity, spec)   # stateless → None
    if stateless:               emit TICKET  # bounded cost, always
    elif last is None:          emit FULL    # first time this session
    elif last.sha256 != h:      emit FULL    # spec changed → re-teach
    elif reset since last:      emit FULL    # the text is gone, re-teach
    elif within window:         silent       # fixed window; no state append
    else:                       emit TICKET  # refresh attention cheaply

A FULL block inlines the (budgeted) spec body with a sha256 attr; a TICKET is
a short reminder pointing back at the spec. Both append a state record; silent
hits do not (fixed window, not sliding — continuous editing is exactly when
drift is worst).

Identity (misfire asymmetry: a collision that MISSES an injection is
unacceptable; drift that OVER-injects is fine): the session/window key is
delegated to common.active_task.resolve_context_key — the shared resolver
every other hook uses — called payload-first, environment-inclusive second,
so two live sessions can never collapse onto one exported env value. A
`+a-<agent_id>` suffix (appended after each part is sanitized) keeps a
subagent's state separate from its parent's. When the resolver is unavailable
(older installed scripts tree), a minimal payload-only ladder (session keys,
then transcript hash) keeps the hook working. No key from any source, or an
unwritable state dir → stateless: no state IO at all, every hit is a TICKET
(circuit breaker — never a FULL re-emission loop).

State: user-global, out of the repo, one append-only JSONL file per identity
under ${TRELLIS_SPEC_STATE_DIR:-~/.trellis/spec-inject}/<project16>/<identity>.jsonl.
SessionStart(clear|compact) appends an opaque reset marker to the base session
shard; parent and subagent emission histories stay separate but observe that
shared marker. A best-effort fcntl lock is held across read→decide→append;
where fcntl is unavailable (Windows) the worst case is a duplicate injection.
A once-per-hour GC prunes conforming shards older than 48 h.

Budget (config.yaml `spec_injection:`): per-spec cap `max_spec_chars`
(default 9400) with code-point truncation + in-body notice; per-event cap
`max_total_chars` (default 9500 — below Claude Code's documented
additionalContext ceiling, and enforced directly for Codex). Once the total
budget is exhausted, remaining FULL bodies degrade to one <spec-index> block;
tickets are counted last and dropped (with a stderr warning) only if even they
do not fit.

Refresh window (config.yaml `spec_injection:`): `refresh_window_seconds`
(default 2700; `0` = never refresh unchanged content solely because time
passed).

Fail-open on errors: non-matching events, malformed paths, no matches, or any
internal error → exit 0 with no stdout (stderr warnings allowed). The only
deliberate block is a Codex patch that just received a FULL governing spec.
"""
from __future__ import annotations

# IMPORTANT: Suppress all warnings FIRST
import warnings
warnings.filterwarnings("ignore")

import hashlib
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

# IMPORTANT: Force UTF-8 on Windows for the streams this hook uses.
# stdin carries the payload (non-ASCII file paths), stdout carries the spec
# bodies, stderr carries warnings that quote both; without this the default
# ANSI codepage raises UnicodeDecodeError / UnicodeEncodeError.
if sys.platform.startswith("win"):
    import io as _io
    for _stream_name in ("stdin", "stdout", "stderr"):
        _stream = getattr(sys, _stream_name, None)
        if _stream is None:
            continue
        if hasattr(_stream, "reconfigure"):
            try:
                _stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
            except Exception:
                pass  # Optional Windows stream setup; keep hook startup non-fatal.
        elif hasattr(_stream, "detach"):
            try:
                setattr(sys, _stream_name, _io.TextIOWrapper(_stream.detach(), encoding="utf-8", errors="replace"))
            except Exception:
                pass  # Optional Windows stream setup; keep hook startup non-fatal.


# =============================================================================
# Constants
# =============================================================================

DIR_WORKFLOW = ".trellis"
DIR_SPEC = "spec"

# Tools whose events trigger spec matching (Claude Code tool names). Touching a
# file — even a Read — counts; the miss path stays a fast exit. Overridable via
# config `spec_injection.tools` (e.g. to drop "Read").
DEFAULT_EDIT_TOOLS = ("Read", "Edit", "Write", "MultiEdit")

# Budget defaults sized against Claude Code's documented 10,000-CHARACTER
# additionalContext ceiling — stay under with margin. The <spec-context>
# wrapper (~152 chars with typical rel paths) counts against the total, so a
# spec lands whole only up to ~9348 chars; at the per-spec cap the block is
# derived-truncated further to fit the event ceiling. `0` = unlimited.
DEFAULT_MAX_SPEC_CHARS = 9400
DEFAULT_MAX_TOTAL_CHARS = 9500

# Refresh-window default. `0` = never refresh solely because time passed.
DEFAULT_REFRESH_WINDOW_SECONDS = 2700

# State-file base dir (overridable for tests / hermeticity) and GC policy.
STATE_ENV_DIR = "TRELLIS_SPEC_STATE_DIR"
STATE_DEFAULT_DIR = "~/.trellis/spec-inject"
GC_MARKER = ".last-gc"
GC_INTERVAL_SECONDS = 60 * 60          # GC runs at most once per hour
STATE_MAX_AGE_SECONDS = 48 * 60 * 60   # shards older than this are pruned

# GC scope: exactly `<base>/<project16>/<identity>[.<pid>].jsonl`, never a
# recursive walk — a hostile or mistyped TRELLIS_SPEC_STATE_DIR must not turn
# this hook into an unlink loop over someone's files. The optional `.<pid>`
# alternative covers shards written by the pre-lock layout.
GC_PROJECT_DIR_RE = re.compile(r"^[0-9a-f]{16}$")
# `+` is part of the identity charset: subagent shards use the `+a-<agent_id>`
# suffix (contract amendment 2 — without it those shards were never pruned).
GC_SHARD_NAME_RE = re.compile(r"^[A-Za-z0-9_+-]+(\.[0-9]+)?\.jsonl$")
PATCH_PATH_RE = re.compile(
    r"^\*\*\* (?:(?:Add|Update|Delete) File|Move to): (.+)$"
)

def _warn(message: str) -> None:
    print(f"[inject-spec-context] WARN: {message}", file=sys.stderr)


def _patch_paths(command: str) -> list[str]:
    """Return file paths from the shared apply_patch grammar."""
    paths: list[str] = []
    for line in command.splitlines():
        match = PATCH_PATH_RE.fullmatch(line)
        if match:
            path = match.group(1).strip()
            if path and path not in paths:
                paths.append(path)
    return paths


def _agent_id(payload: dict) -> str:
    """The subagent id carried by the event, or "" for a main-session event."""
    raw = payload.get("agent_id")
    return raw.strip() if isinstance(raw, str) else ""


def find_trellis_root(start: Path) -> Path | None:
    """Walk up from start to find the directory containing .trellis/.

    Handles CWD drift: subdirectory launches, monorepo packages, etc.
    Returns None if no .trellis/ found (silent no-op).
    """
    cur = start.resolve()
    while cur != cur.parent:
        if (cur / DIR_WORKFLOW).is_dir():
            return cur
        cur = cur.parent
    return None


def _scripts_dir_on_path(root: Path) -> None:
    scripts_dir = root / DIR_WORKFLOW / "scripts"
    if str(scripts_dir) not in sys.path:
        sys.path.insert(0, str(scripts_dir))


# =============================================================================
# Config (.trellis/config.yaml `spec_injection:` section)
# =============================================================================


def _read_trellis_config(root: Path) -> dict:
    """Load .trellis/config.yaml via the bundled trellis_config helper.

    The helper lives in .trellis/scripts/common; the hook lives outside the
    scripts tree, so we extend sys.path before importing.
    """
    _scripts_dir_on_path(root)
    try:
        from common.trellis_config import read_trellis_config  # type: ignore[import-not-found]
    except Exception:
        return {}
    try:
        return read_trellis_config(root)
    except Exception:
        return {}


def _parse_tools(raw: object) -> tuple[str, ...] | None:
    """Parse `spec_injection.tools` into a tuple of tool names.

    Two grammars, because the bundled YAML reader hands the value over in two
    shapes: a block list (``- Edit`` items) arrives as a list, a flow sequence
    (``tools: [Edit, Write]``) arrives as the raw string. ``[]`` in either
    shape is a deliberate "never trigger" and is respected. Returns None for a
    value that is neither (the caller warns and keeps the defaults).
    """
    if isinstance(raw, list):
        return tuple(t.strip() for t in raw if isinstance(t, str) and t.strip())
    if isinstance(raw, str):
        text = raw.strip()
        if text.startswith("[") and text.endswith("]"):
            items = (part.strip().strip("\"'").strip() for part in text[1:-1].split(","))
            return tuple(item for item in items if item)
    return None


def get_spec_injection_settings(
    root: Path,
) -> tuple[bool, int, int, int, tuple[str, ...]]:
    """Return (enabled, max_spec_chars, max_total_chars,
    refresh_window_seconds, tools).

    Reads the ``spec_injection:`` section of ``.trellis/config.yaml``:

        spec_injection:
          enabled: true
          max_spec_chars: 9400
          max_total_chars: 9500
          refresh_window_seconds: 2700
          tools:
            - Read
            - Edit
            - Write
            - MultiEdit

    Missing keys use their defaults; ``0`` disables the corresponding limit
    (``max_spec_chars: 0`` = inline the whole body, ``max_total_chars: 0`` =
    no per-event ceiling) or refresh (window keys). ``tools`` also accepts a
    flow sequence (``tools: [Edit, Write]``), and ``tools: []`` disables every
    trigger. Invalid values fall back to the default for that key with a
    stderr warning; tool names outside the known set warn once.
    """
    enabled = True
    tools = DEFAULT_EDIT_TOOLS
    numbers = {
        "max_spec_chars": DEFAULT_MAX_SPEC_CHARS,
        "max_total_chars": DEFAULT_MAX_TOTAL_CHARS,
        "refresh_window_seconds": DEFAULT_REFRESH_WINDOW_SECONDS,
    }

    config = _read_trellis_config(root)
    section = config.get("spec_injection") if isinstance(config, dict) else None
    if isinstance(section, dict):
        raw_enabled = section.get("enabled", True)
        if isinstance(raw_enabled, bool):
            enabled = raw_enabled
        else:
            s = str(raw_enabled).strip().lower()
            if s in ("false", "no", "0", "off"):
                enabled = False
            elif s not in ("true", "yes", "1", "on"):
                _warn(
                    f"invalid spec_injection.enabled value: {raw_enabled!r}; "
                    f"using true (default)"
                )

        # int() coercion stays local to this hook by decision (audit round,
        # 2026-07-25): widening the shared common/config.py helpers has more
        # blast radius than this small duplication costs.
        for key, default_value in list(numbers.items()):
            if key not in section:
                continue
            raw = section[key]
            try:
                value = int(raw)
            except (TypeError, ValueError):
                value = -1
            if value < 0:
                _warn(
                    f"invalid spec_injection.{key} value: {raw!r}; "
                    f"using default {default_value}"
                )
                continue
            numbers[key] = value

        if "tools" in section:
            parsed_tools = _parse_tools(section["tools"])
            if parsed_tools is None:
                _warn(
                    f"invalid spec_injection.tools value: {section['tools']!r}; "
                    f"using default {list(DEFAULT_EDIT_TOOLS)}"
                )
            else:
                tools = parsed_tools
                unknown = [t for t in tools if t not in DEFAULT_EDIT_TOOLS]
                if unknown:
                    _warn(
                        f"unknown spec_injection.tools entries {unknown} — "
                        f"they will never match; known tools: "
                        f"{list(DEFAULT_EDIT_TOOLS)}"
                    )

    return (
        enabled,
        numbers["max_spec_chars"],
        numbers["max_total_chars"],
        numbers["refresh_window_seconds"],
        tools,
    )


# =============================================================================
# Identity ladder
# =============================================================================


def _sanitize(raw: str) -> str:
    """Map a session/agent id to a filename-safe, collision-free token.

    A readable head (the first 80 characters, every character outside
    ``[A-Za-z0-9_-]`` replaced one-for-one by ``-``) plus, whenever anything
    was replaced or the id was longer than 80 characters, ``-`` and 8 hex of
    sha256(raw). The suffix is what makes the mapping injective: without it,
    "a/b" and "a:b" — or two ids sharing an 80-character prefix — would fold
    onto one state file, and a collision that MISSES an injection is the
    unacceptable failure. Output stays inside the GC name class.
    """
    raw = raw.strip()
    head = raw[:80]
    safe = re.sub(r"[^A-Za-z0-9_-]", "-", head)
    if safe != head or len(raw) > 80:
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:8]
        return f"{safe}-{digest}"
    return safe


def _shared_context_key(root: Path, payload: dict) -> str | None:
    """Session/window key from the shared resolver every other hook uses.

    ``common.active_task.resolve_context_key`` is the single source of truth
    for session identity: payload keys in all casings (``session_id`` /
    ``sessionId`` / ``sessionID``, conversation and transcript variants),
    nested payload shapes, the explicit ``TRELLIS_CONTEXT_ID`` override,
    per-platform env fallbacks, and Cursor shell tickets — plus the platform
    fixes accumulated behind them. Payload identity is preferred over
    environment context so two live sessions can never collapse onto one
    exported env value (collision → missed injection is the unacceptable
    direction); the environment pass still runs when the payload carries
    nothing.
    """
    try:
        _scripts_dir_on_path(root)
        from common.active_task import resolve_context_key  # type: ignore[import-not-found]

        key = resolve_context_key(payload, allow_environment_context=False)
        if key:
            return key
        return resolve_context_key(payload)
    except Exception:
        return None


def resolve_base_identity(root: Path, payload: dict) -> tuple[str, bool]:
    """Return the base session identity for reset and refresh state.

    When the shared resolver is unavailable (older installed scripts tree), a
    minimal payload-only ladder keeps the hook working. ``stateless=True``
    means no state IO is possible.
    """
    identity_payload = payload
    if payload.get("hook_event_name") == "SessionStart":
        # In this event `source` means startup/clear/compact, not platform.
        # The shared resolver also accepts a generic `source` platform hint,
        # so remove the lifecycle field to keep the same session identity as
        # later PostToolUse events.
        identity_payload = dict(payload)
        identity_payload.pop("source", None)
    key = _shared_context_key(root, identity_payload)

    if not key:
        # Minimal payload-only fallback for scripts trees that predate
        # resolve_context_key. Mirrors its payload lookup order.
        for k in ("session_id", "sessionId", "sessionID"):
            value = payload.get(k)
            if isinstance(value, str) and value.strip():
                key = "s-" + value.strip()
                break
        if not key:
            transcript = payload.get("transcript_path")
            if isinstance(transcript, str) and transcript.strip():
                digest = hashlib.sha256(
                    transcript.strip().encode("utf-8")
                ).hexdigest()
                key = "t-" + digest[:16]

    if not key:
        return "", True

    return _sanitize(key), False


# =============================================================================
# State (one append-only JSONL file per identity, locked, user-global)
# =============================================================================


def _state_base_dir() -> Path:
    override = os.environ.get(STATE_ENV_DIR)
    if override and override.strip():
        return Path(override.strip())
    return Path(os.path.expanduser(STATE_DEFAULT_DIR))


def _project_id(root: Path) -> str:
    return hashlib.sha256(os.path.realpath(str(root)).encode("utf-8")).hexdigest()[:16]


def _maybe_gc(base_dir: Path) -> None:
    """Prune conforming shards older than 48 h, at most once per hour.

    Scope is exact-depth (``<base>/<project16>/<shard>.jsonl``) and name-gated;
    foreign files and directories are never touched. Containment is enforced
    against symlinks on both levels — a symlinked project dir or shard is
    skipped outright, and every unlink candidate must still be under the
    resolved base after realpath — so a planted link cannot walk this GC out
    of its own tree. Best-effort, errors ignored.
    """
    try:
        base_real = os.path.realpath(str(base_dir))
        marker = base_dir / GC_MARKER
        now = time.time()
        try:
            age = now - marker.stat().st_mtime
        except OSError:
            age = None
        if age is not None and age < GC_INTERVAL_SECONDS:
            return
        try:
            base_dir.mkdir(parents=True, exist_ok=True)
            marker.touch()
        except OSError:
            return
        try:
            project_dirs = list(base_dir.iterdir())
        except OSError:
            return
        for project_dir in project_dirs:
            if not GC_PROJECT_DIR_RE.match(project_dir.name):
                continue
            try:
                if project_dir.is_symlink() or not project_dir.is_dir():
                    continue
                shards = list(project_dir.iterdir())
            except OSError:
                continue
            for shard in shards:
                if not GC_SHARD_NAME_RE.match(shard.name):
                    continue
                try:
                    if shard.is_symlink() or not shard.is_file():
                        continue
                    shard_real = os.path.realpath(str(shard))
                    if not shard_real.startswith(base_real + os.sep):
                        continue
                    if now - shard.stat().st_mtime > STATE_MAX_AGE_SECONDS:
                        shard.unlink()
                except OSError:
                    continue
    except Exception:
        pass


def open_shard(shard_path: Path) -> int | None:
    """Open (creating) the identity's shard for read+append.

    Doubles as the writability probe: a failure here trips the circuit breaker
    and the event runs stateless (ticket-only), which is bounded, instead of
    re-emitting full specs on every event forever.
    """
    try:
        shard_path.parent.mkdir(parents=True, exist_ok=True)
    except OSError:
        _warn(f"state dir {shard_path.parent} unusable — running stateless")
        return None
    try:
        return os.open(
            str(shard_path),
            os.O_RDWR | os.O_CREAT | os.O_APPEND,
            0o644,
        )
    except OSError:
        _warn(f"state shard {shard_path} unusable — running stateless")
        return None


def lock_shard(fd: int) -> None:
    """Best-effort exclusive lock held across read→decide→append.

    Closes the duplicate-injection race between concurrent hook processes on
    POSIX. No fcntl (Windows) or an unsupported filesystem → no lock; the
    worst case is a duplicate injection, never a lost one.
    """
    try:
        import fcntl

        fcntl.flock(fd, fcntl.LOCK_EX)
    except Exception:
        pass


def unlock_shard(fd: int) -> None:
    try:
        import fcntl

        fcntl.flock(fd, fcntl.LOCK_UN)
    except Exception:
        pass


def load_state(
    fd: int,
    state_version: int,
) -> tuple[dict[str, dict], str | None] | None:
    """Read the shard through the already-open fd; newest record per spec wins
    (``ts`` decides, and on an exact tie the later line in the file does —
    appends are ordered, and two records one float apart must not resolve to
    the older one). The latest reset marker is returned separately. Malformed
    lines and foreign schema versions are skipped silently; read failures
    return None so the caller uses stateless ticket mode."""
    result: dict[str, dict] = {}
    latest_reset: str | None = None
    try:
        os.lseek(fd, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        while True:
            chunk = os.read(fd, 1 << 20)
            if not chunk:
                break
            chunks.append(chunk)
    except OSError:
        return None

    text = b"".join(chunks).decode("utf-8", errors="replace")
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(record, dict):
            continue
        if record.get("v") != state_version:
            continue
        spec = record.get("spec")
        reset = record.get("reset")
        if not isinstance(spec, str):
            if isinstance(reset, str) and reset:
                latest_reset = reset
            continue
        ts = record.get("ts")
        if not isinstance(ts, (int, float)):
            continue
        previous = result.get(spec)
        if previous is None or ts >= previous.get("ts", float("-inf")):
            result[spec] = record
    return result, latest_reset


def append_records(fd: int, records: list[dict]) -> bool:
    """Append records as JSONL (O_APPEND) and report whether all bytes landed."""
    if not records:
        return True
    try:
        blob = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records)
        encoded = blob.encode("utf-8")
        if os.write(fd, encoded) != len(encoded):
            _warn("could not write complete state shard — state may be incomplete")
            return False
        return True
    except OSError:
        _warn("could not write state shard — state may be incomplete")
        return False


# =============================================================================
# Entry
# =============================================================================


def main() -> int:
    if os.environ.get("TRELLIS_HOOKS") == "0" or os.environ.get("TRELLIS_DISABLE_HOOKS") == "1":
        return 0

    try:
        input_data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError):
        return 0
    if not isinstance(input_data, dict):
        return 0

    cwd = input_data.get("cwd") or os.getcwd()
    root = find_trellis_root(Path(cwd))
    if root is None:
        return 0
    # Bail out before any spec scan when the project has no spec directory.
    if not (root / DIR_WORKFLOW / DIR_SPEC).is_dir():
        return 0

    (
        enabled,
        max_spec_chars,
        max_total_chars,
        win_seconds,
        tools,
    ) = get_spec_injection_settings(root)
    if not enabled:
        return 0

    _scripts_dir_on_path(root)
    try:
        from common.spec_inject import STATE_VERSION  # type: ignore[import-not-found]
    except Exception:
        return 0

    if input_data.get("hook_event_name") == "SessionStart":
        if input_data.get("source") not in ("clear", "compact"):
            return 0
        base_identity, stateless = resolve_base_identity(root, input_data)
        if stateless:
            _warn("SessionStart reset has no stable session identity")
            return 0
        base_dir = _state_base_dir()
        _maybe_gc(base_dir)
        reset_path = base_dir / _project_id(root) / f"{base_identity}.jsonl"
        reset_fd = open_shard(reset_path)
        if reset_fd is None:
            return 0
        try:
            lock_shard(reset_fd)
            append_records(
                reset_fd,
                [{"v": STATE_VERSION, "reset": uuid.uuid4().hex, "ts": time.time()}],
            )
        finally:
            unlock_shard(reset_fd)
            try:
                os.close(reset_fd)
            except OSError:
                pass
        return 0

    event_name = input_data.get("hook_event_name")
    tool_name = input_data.get("tool_name", "") or input_data.get("toolName", "")
    if not isinstance(tool_name, str) or not tool_name:
        return 0
    is_pre_tool_use = event_name == "PreToolUse"
    is_patch_tool = event_name == "PreToolUse" and tool_name == "apply_patch"
    logical_tool = "Edit" if is_patch_tool else tool_name
    # An empty `tools` list is the documented "disable every trigger" switch.
    if not tools or logical_tool not in tools:
        return 0

    # snake_case is Claude Code's shape; camelCase keeps parity with the
    # sibling hooks that already accept both (other platforms emit toolInput).
    tool_input = input_data.get("tool_input")
    if not isinstance(tool_input, dict):
        tool_input = input_data.get("toolInput")
    if not isinstance(tool_input, dict):
        return 0
    try:
        from common.spec_match import (  # type: ignore[import-not-found]
            match_specs_for_file,
            normalize_repo_relative,
        )
        from common.spec_inject import (  # type: ignore[import-not-found]
            assemble_payload,
        )
    except Exception:
        return 0  # matching/decision engine unavailable — degrade to nothing

    if is_patch_tool:
        command = tool_input.get("command")
        if not isinstance(command, str):
            return 0
        raw_paths = _patch_paths(command)
    else:
        file_path = tool_input.get("file_path")
        if not isinstance(file_path, str) or not file_path.strip():
            return 0
        raw_paths = [file_path.strip()]

    file_paths: list[str] = []
    for raw_path in raw_paths:
        normalized = normalize_repo_relative(root, raw_path)
        if normalized is not None and normalized not in file_paths:
            file_paths.append(normalized)
    if not file_paths:
        return 0

    matches = []
    match_files: dict[str, str] = {}
    for file_path in file_paths:
        for match in match_specs_for_file(root, file_path):
            if match.rel_path in match_files:
                continue
            matches.append(match)
            match_files[match.rel_path] = file_path
    if not matches:
        return 0

    base_identity, stateless = resolve_base_identity(root, input_data)
    identity = base_identity
    agent = _agent_id(input_data)
    if agent:
        identity += "+a-" + _sanitize(agent)

    state_records: dict[str, dict] = {}
    clock = {"reset": None, "ts": time.time()}
    fd: int | None = None

    if not stateless:
        base_dir = _state_base_dir()
        _maybe_gc(base_dir)
        project_dir = base_dir / _project_id(root)
        base_fd = open_shard(project_dir / f"{base_identity}.jsonl")
        if base_fd is None:
            # Circuit breaker: unwritable state → ticket-only for this event.
            stateless = True
        else:
            lock_shard(base_fd)
            base_snapshot = load_state(base_fd, STATE_VERSION)
            if base_snapshot is None:
                stateless = True
                unlock_shard(base_fd)
                os.close(base_fd)
            else:
                base_records, reset_id = base_snapshot
                if identity == base_identity:
                    fd = base_fd
                    state_records = base_records
                else:
                    unlock_shard(base_fd)
                    os.close(base_fd)
                    fd = open_shard(project_dir / f"{identity}.jsonl")
                    if fd is None:
                        stateless = True
                    else:
                        lock_shard(fd)
                        snapshot = load_state(fd, STATE_VERSION)
                        if snapshot is None:
                            stateless = True
                            unlock_shard(fd)
                            os.close(fd)
                            fd = None
                        else:
                            state_records, _ = snapshot

                if not stateless:
                    clock = {
                        "reset": reset_id,
                        "ts": time.time(),
                    }

    edited_rel = match_files[matches[0].rel_path]
    records_persisted = True
    try:
        payload, records = assemble_payload(
            edited_rel,
            matches,
            stateless,
            state_records,
            clock,
            max_spec_chars,
            max_total_chars,
            win_seconds,
            match_files=match_files,
        )
        if fd is not None and records:
            records_persisted = append_records(fd, records)
    finally:
        if fd is not None:
            unlock_shard(fd)
            try:
                os.close(fd)
            except OSError:
                pass

    if not payload:
        return 0

    hook_specific_output = {
        "hookEventName": "PreToolUse" if is_pre_tool_use else "PostToolUse",
        "additionalContext": payload,
    }
    if (
        is_pre_tool_use
        and records_persisted
        and any(record.get("mode") == "full" for record in records)
    ):
        hook_specific_output.update(
            {
                "permissionDecision": "deny",
                "permissionDecisionReason": (
                    "Trellis injected governing specs. Review them, then retry "
                    "this tool call."
                ),
            }
        )
    output = {"hookSpecificOutput": hook_specific_output}
    print(json.dumps(output, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # Hook failures must never break the tool result or the session.
        sys.exit(0)
