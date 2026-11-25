import importlib.util
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HARNESS))


def load_script(rel: str):
    path = HARNESS / rel
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TempRepo:
    """커밋 하나가 있는 임시 git 저장소. 상태 디렉터리도 그 안의 `.state`로 돌린다."""

    def __enter__(self) -> Path:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name).resolve()
        self._old_env = os.environ.get('HARNESS_STATE_DIR')
        os.environ['HARNESS_STATE_DIR'] = str(self.root / '.state')
        for args in (['init', '-q'], ['config', 'user.email', 't@t'], ['config', 'user.name', 't']):
            subprocess.run(['git', '-C', str(self.root), *args], check=True)
        (self.root / '.gitignore').write_text('.state/\n')
        (self.root / 'README.md').write_text('x\n')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'commit', '-qm', 'init'], check=True)
        return self.root

    def __exit__(self, *exc) -> None:
        if self._old_env is None:
            os.environ.pop('HARNESS_STATE_DIR', None)
        else:
            os.environ['HARNESS_STATE_DIR'] = self._old_env
        self._tmp.cleanup()
