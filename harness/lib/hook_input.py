"""Claude Code와 Codex 훅 입력·출력의 차이를 흡수하는 어댑터."""

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

TOOLS = ('claude', 'codex')


@dataclass(frozen=True)
class HookInput:
    tool: str
    event: str
    session_id: str | None
    cwd: Path
    stop_hook_active: bool = False
    transcript_path: str | None = None
    tool_name: str | None = None
    command: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)


def parse(tool: str, text: str) -> HookInput:
    if tool not in TOOLS:
        raise ValueError(f'unknown tool: {tool}')
    payload = json.loads(text or '{}')
    tool_input = payload.get('tool_input')
    command = tool_input.get('command') if isinstance(tool_input, dict) else None
    # Codex는 셸 명령을 인자 배열로 넘기는 경우가 있다.
    if isinstance(command, list):
        command = ' '.join(str(part) for part in command)
    return HookInput(
        tool=tool,
        event=payload.get('hook_event_name', ''),
        session_id=payload.get('session_id') or None,
        cwd=Path(payload.get('cwd') or '.').resolve(),
        stop_hook_active=bool(payload.get('stop_hook_active', False)),
        transcript_path=payload.get('transcript_path') or None,
        tool_name=payload.get('tool_name') or None,
        command=command,
        raw=payload,
    )


def stop_block(reason: str) -> str:
    """두 도구 모두 Stop 훅의 `decision: block`과 `reason`을 이어지는 지시로 받는다."""
    return json.dumps({'decision': 'block', 'reason': reason}, ensure_ascii=False)
