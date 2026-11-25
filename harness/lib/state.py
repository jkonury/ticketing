"""파이프라인 활성 상태 파일(`<state_dir>/active.json`) 읽기·쓰기."""

import json
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

STATE_DIR_ENV = 'HARNESS_STATE_DIR'
DEFAULT_STATE_DIR = '.metrics'
MAX_GATE_BLOCKS = 3


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def repo_root(cwd: Path) -> Path:
    out = subprocess.run(
        ['git', '-C', str(cwd), 'rev-parse', '--show-toplevel'],
        capture_output=True, text=True, check=True, timeout=10,
    )
    return Path(out.stdout.strip())


def state_path(root: Path) -> Path:
    return Path(os.environ.get(STATE_DIR_ENV) or root / DEFAULT_STATE_DIR) / 'active.json'


def new_task(task_id: str, ac: list[str]) -> dict[str, Any]:
    return {
        'id': task_id,
        'ac': ac,
        'started_at': now(),
        'gate_blocks': 0,
        'gate_unresolved': False,
        'last_pass_fingerprint': None,
    }


def load(root: Path) -> dict[str, Any] | None:
    path = state_path(root)
    if not path.is_file():
        return None
    return json.loads(path.read_text())


def save(root: Path, state: dict[str, Any]) -> None:
    path = state_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    # 훅과 CLI가 같은 파일을 쓰므로 부분 쓰기가 보이지 않게 교체한다.
    with tempfile.NamedTemporaryFile('w', dir=path.parent, delete=False, suffix='.tmp') as tmp:
        json.dump(state, tmp, ensure_ascii=False, indent=2)
        tmp.write('\n')
    Path(tmp.name).replace(path)


def clear(root: Path) -> None:
    state_path(root).unlink(missing_ok=True)
