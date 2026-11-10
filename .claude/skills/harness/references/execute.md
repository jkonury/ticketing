# execute.py 동작

사용자가 실행 방식이나 실패 처리에 대해 물을 때 참고한다.

- **worktree 확인** — 에이전트는 `--dangerously-skip-permissions`로 실행되므로 git worktree 안에서 실행한다. worktree 밖이면 경고 후 사용자 확인을 요청한다. 백그라운드 실행처럼 표준 입력이 없으면 확인 없이 종료 코드 0으로 끝나므로, 아무 step도 실행되지 않았다면 worktree 경로를 먼저 확인한다.
- **step 실행** — step마다 새 Claude 세션(`--strict-mcp-config`, `--tools Bash,Read,Write,Edit`, step의 `model` 지정 시 `--model`, `reasoning_effort` 지정 시 `--effort`)을 띄우고, 이전 step들의 `summary`를 `[CONTEXT]`로 전달한다. 직전 step은 전문, 그 이전 step은 120자로 줄여 싣는다(`CONTEXT_FULL_STEPS`, `CONTEXT_OLD_SUMMARY_CHARS`).
- **완료 판정** — 에이전트가 `completed`로 보고하면 execute.py가 `ac` 커맨드를 직접 실행한다. 실패 시 `error`로 덮어쓰고 출력을 `[RETRY CONTEXT]`로 넘겨 재시도한다.
- **재시도** — 프로세스 실패, 타임아웃, `index.json` 미갱신, AC 실패 모두 step당 최대 3회 시도한다. 이전 시도의 변경은 남겨 두고 오류 부분만 고치게 한다. `blocked`는 즉시 중단한다.
- **커밋** — AC를 통과하면 `git add -A` 후 `harness({phase}): step{NN} {name}`으로 커밋한다. step 1개 = 커밋 1개이므로, worktree는 깨끗한 상태에서 시작한다.
- **추론 강도** — `python3 scripts/execute.py {phase} --reasoning-effort medium`처럼 실행 옵션으로 모든 step의 값을 덮어쓸 수 있다. 시작 전에 남은 step의 값을 모두 검증해, 잘못된 값 때문에 앞 step만 실행·커밋된 채 멈추지 않게 한다. 시도별 JSON에 `requested_reasoning_effort`와 출처(`cli`/`step`/`cli_default`)를 남긴다. 이 값은 요청값이고 실제 적용값은 아니다.
- **사용량 비교** — `python3 scripts/usage_report.py {phase} [--run RUN_ID]`가 step별 Input Total(캐시 포함)·Uncached·Output·Turns를 집계한다. 모델·강도·step 분할을 바꿨다면 이 표로 이전 실행과 비교한다.
- **로그** — 실행마다 `phases/{phase}/logs/{run_id}/`에 `run.json`과 시도별 `step{NN}-attempt{NN}.log`(프롬프트·출력), `.json`(메타데이터·사용량·AC 결과)을 남긴다 (gitignore 대상, worktree 삭제 시 함께 사라짐).
- phase 완료 후 사용자가 브랜치를 리뷰하고 merge한다. 이후 `scripts/prune_phase.py`로 계획 파일을 정리한다 (harness SKILL.md E-3).
- **선행 phase 검사** — 시작 전에 최상위 `phases/index.json`의 `depends_on`을 읽어, 선행 phase의 step이 현재 브랜치에서 모두 `completed`가 아니면 종료 코드 1로 중단한다. `prune_phase.py`로 정리되어 목록에서 빠진 선행 phase는 완료로 본다.
- execute.py는 최상위 `phases/index.json`에 쓰지 않는다. 완료 여부는 phase별 `index.json`의 step 상태로 판단한다.

Codex CLI로 실행하는 `scripts/execute-codex.py`는 `scripts/README.md`를 참고한다.
