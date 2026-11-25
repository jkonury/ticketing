"""command_policy.py의 비밀 파일 차단 규칙을 검사한다. 실행: python3 -m unittest discover -s scripts/agent/hook -p 'test_*.py'"""
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('command_policy', Path(__file__).with_name('command_policy.py'))
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
ROOT = Path(__file__).resolve().parents[3]


class SecretFileTest(unittest.TestCase):
    def assert_blocked(self, command: str) -> None:
        self.assertEqual(POLICY.SECRET_HINT, POLICY.reason(command, ROOT), command)

    def assert_not_blocked(self, command: str) -> None:
        self.assertIsNone(POLICY.reason(command, ROOT), command)

    def test_blocks_reading_secret_files(self) -> None:
        for command in ('cat .env', 'head -5 web/.env.local', 'rg -n KEY core/src/main/resources/application-local.yml',
                        'wc -l < .env', 'source .env', 'cp .env /tmp/leak', 'ls && sed -n 1p ./.env.production',
                        "bash -c 'cat .env'"):
            with self.subTest(command=command):
                self.assert_blocked(command)

    def test_allows_examples_and_indirect_use(self) -> None:
        for command in ('cat .env.example', 'cp web/.env.example web/.env.local', 'ls -la .env',
                        './scripts/e2e/run.sh', 'rg -n TICKETING_JWT_SECRET .env.example', 'docker compose up -d'):
            with self.subTest(command=command):
                self.assert_not_blocked(command)


if __name__ == '__main__':
    unittest.main()
