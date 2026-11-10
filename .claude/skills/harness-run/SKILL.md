---
name: harness-run
description: harness로 생성한 phase를 현재 worktree 세션(wt switch -c {phase})에서 scripts/execute.py로 실행하고 결과를 보고한다.
argument-hint: <phase>
disable-model-invocation: true
---

# Harness 실행

인자: `$ARGUMENTS` — 첫 번째는 phase 디렉토리명(`{phase}`), 두 번째는 worktree를 만든 base 브랜치(`{base}`, `wt switch -c {phase} --base {base}`와 같은 값). 두 번째 인자가 없으면 사용자에게 물어본다. 이유: phase 파일이 main에 없는 경우가 많아 `main`을 기본값으로 두면 결과 보고의 커밋 범위가 틀어진다.
phase 파일 구조와 execute.py 동작은 [harness 스킬](../harness/SKILL.md)과 [references/execute.md](../harness/references/execute.md)를 참고한다.

## 1. 사전 확인

- **worktree 여부** — `git rev-parse --git-dir`과 `git rev-parse --git-common-dir`가 같으면 worktree가 아니다. 이때는 실행하지 말고 `wt switch -c {phase}` 실행 후 claude 새 세션을 열도록 안내한다. 이유: 하위 세션이 `--dangerously-skip-permissions`로 실행된다.
- **phase 파일 동기화** — `phases/{phase}/index.json`이 없으면 중단한다. 대개 `--base` 없이 worktree를 만들어 main에서 갈라진 경우다. `git merge-base --is-ancestor {base} HEAD`로 확인하고, 실패하면 worktree를 지우고 `wt switch -c {phase} --base {base}`로 다시 만들도록 안내한다.
- **선행 phase** — 최상위 `phases/index.json`에서 이 phase의 `depends_on`을 읽어, 각 선행 phase의 `phases/{dep}/index.json` step이 모두 `completed`인지 보여 준다. 미완료면 실행하지 않고 선행 phase를 `{base}`에 merge한 뒤 worktree를 다시 만들도록 안내한다. 목록에 없는 선행 phase는 이미 정리(prune)된 것이므로 완료로 본다. execute.py도 같은 검사를 하지만, 백그라운드 실행 전에 미리 알리기 위해 여기서 확인한다.
- **깨끗한 작업 트리** — `git status --porcelain` 출력이 있으면 중단한다. 이유: execute.py가 step마다 `git add -A`로 커밋하므로 관련 없는 변경이 섞인다.
- **phase 상태** — `phases/{phase}/index.json`의 step 목록과 상태를 사용자에게 보여 준다. `completed`인 step은 건너뛴다는 점도 알린다.

## 2. 실행

step당 최대 30분 × 재시도가 걸리므로 Bash를 `run_in_background: true`로 호출한다.

```sh
python3 "$(git rev-parse --show-toplevel)/scripts/execute.py" {phase}
```

- 하위 세션이 `--dangerously-skip-permissions`로 실행되므로 이 호출은 사용자 승인을 거친다. 거부되면 위 명령을 `! <명령>`으로 직접 실행하도록 안내한다.
- 샌드박스 때문에 하위 `claude` 세션이 네트워크·인증 오류로 실패하면, 사용자에게 알리고 샌드박스 해제 여부를 확인한다.
- 실행 직후 아무 step도 시작되지 않고 종료 코드 0으로 끝났다면 worktree 확인 단계에서 종료된 것이다. 1번의 worktree 여부부터 다시 확인한다.
- `선행 phase가 이 브랜치에 완료 상태로 들어와 있지 않습니다`로 종료 코드 1이면 1번의 선행 phase 안내를 따른다.

## 3. 대기

완료되면 알림으로 다시 호출되므로 sleep이나 폴링을 하지 않는다. 사용자가 진행 상황을 물으면 `phases/{phase}/index.json`의 step별 `status`를 읽어 답한다.

## 4. 결과 보고

로그는 실행마다 `phases/{phase}/logs/{run_id}/`에 쌓인다(`run_id`는 실행 시작 시 `실행 ID:`로 출력되며, 가장 최근 디렉토리가 이번 실행이다).

- **성공** — `git log --oneline {base}..HEAD`로 step 커밋 목록, 각 step의 `summary`, 토큰·비용은 `python3 scripts/usage_report.py {phase}`의 step별 표(턴, cache 생성/읽기, 출력, `total_cost_usd` 합계)로 보고한다. 로그가 `logs/<run>/stepNN-attemptNN.json`의 `metrics`에 있어 `.log`를 grep하면 안 된다.
- **`error`/`blocked`** — 해당 step의 `error_message`/`blocked_reason`, 마지막 시도의 `step{NN}-attempt{NN}.json`(AC 결과)과 `.log` 끝부분을 확인해 원인과 해결안을 제시한다. 원인을 고친 뒤 2번 명령을 다시 실행하면 완료된 step은 건너뛰고 이어서 진행한다.

## 5. 마무리

merge, push, worktree 정리는 하지 않는다. 브랜치(`git branch --show-current`)를 리뷰한 뒤 원래 저장소에서 merge하도록 안내한다.
