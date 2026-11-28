---
name: finish-plan
description: 구현을 마친 계획을 PR로 올리고 CI 통과를 확인한 뒤, 검증이 끝나면 계획을 done으로 바꿔 커밋하고 파이프라인을 종료한 다음 PR을 머지한다. /implement로 모든 task를 커밋한 뒤 마무리할 때 사용.
disable-model-invocation: true
---

`/implement`가 모든 task를 커밋한 계획을 PR로 올리고 CI까지 확인해 닫는다. 마지막에 PR을 머지한다. 사용자가 이 스킬을 호출하면 계획 브랜치 push, PR 생성, 계획 완료 커밋, PR 머지를 요청한 것으로 본다.

## 순서

### 1. 사전 확인

- `./harness/bin/pipeline.py status`로 활성 계획과 시작 SHA를 확인한다. 활성 파이프라인이 없거나 계획이 다르면 멈추고 사용자에게 묻는다.
- 계획의 task 체크박스가 모두 체크됐고 작업 트리가 깨끗한지 본다. 미완료 task나 미커밋 변경이 있으면 `/implement`로 돌려보낸다.
- 현재 브랜치가 `main`이면 계획 slug로 `<type>/<slug>` 브랜치를 만들어 옮긴다. 이미 PR이 있으면(`gh pr view`) 새로 만들지 않고 그 PR을 이어 쓴다.

### 2. 로컬 최종 검사

- 저장소 루트에서 `./scripts/agent/test/verify-changed.sh main`과 계획에 명시한 추가 검사를 실행한다. JVM 변경이면 [저장소 테스트 지침](../../../AGENTS.md#6-테스트와-검사)대로 `./scripts/agent/test/run-tests.sh`를 필터 없이 실행한다.
- 로컬 E2E는 사용자가 요청한 경우에만 돌린다. 실패는 그대로 보고하고 고친 뒤 다시 실행한다.

### 3. PR 생성

- `git push -u origin <브랜치>` 후 `gh pr create --base main`으로 만든다. 본문은 [PR 템플릿](../../../.github/pull_request_template.md)을 채운다.
  - 제목은 `<type>(<scope>): <요약>` 형식, 한국어로 쓴다.
  - 변경 내용은 task 커밋 목록에서, 관련은 계획·로드맵 항목·ADR에서 가져온다.
  - 검증 칸에는 2단계에서 실행한 명령과 결과를 적는다. E2E는 "PR의 CI에서 확인"으로 적고 4단계 뒤에 결과로 바꾼다.
  - 도구 서명과 비밀 값은 넣지 않는다.

### 4. CI 확인

- `gh pr checks <PR> --watch --fail-fast`로 모든 체크가 끝날 때까지 기다린다. 대기가 길면 백그라운드로 실행해 알림을 받는다.
- 실패하면 `gh run view <run-id> --log-failed`로 원인을 읽고 코드를 고쳐 검증한 뒤 `fix(<scope>): ...` 커밋으로 push하고 다시 기다린다.
- 테스트 삭제·비활성화, `@Suppress`·baseline 추가, `--no-verify`로 우회하지 않는다. 같은 원인으로 3회 고쳐도 실패하거나 CI 인프라 문제(러너, 네트워크)로 보이면 멈추고 실패 체크와 로그 요약을 사용자에게 보고한다.

### 5. 검증 완료 확인

CI가 모두 통과한 뒤 다음을 확인한다. 하나라도 어긋나면 완료 처리하지 않고 보고한다.

- 계획의 task별 AC와 최종 검사가 모두 충족됐다. 계획과 실제 변경이 어긋나지 않는다.
- 문서와 로드맵 체크박스가 변경과 맞다([AGENTS.md 3절](../../../AGENTS.md#3-작업-방식)).
- PR 본문의 검증 칸이 실제 결과(로컬 검사, CI 체크 이름과 통과 여부)로 채워졌다. `gh pr edit`으로 갱신한다.

### 6. 계획 완료와 파이프라인 종료

1. 계획의 `status`를 `done`으로 바꾸고 계획 파일만 스테이지해 `docs(workflow): <계획 제목> 계획 완료`로 커밋한 뒤 push한다.
2. 이 커밋으로 다시 도는 CI도 통과하는지 확인한다. 문서만 바뀌므로 `./scripts/agent/docs/check-docs.sh`를 로컬에서 먼저 실행한다.
3. `./harness/bin/pipeline.py end`로 파이프라인을 종료한다. 종료가 실패하면 상태와 메시지를 보고하고 머지하지 않는다.

### 7. 머지

- 머지 직전에 `gh pr view <PR> --json mergeable,mergeStateStatus,statusCheckRollup`로 충돌이 없고 모든 체크가 통과했는지 다시 확인한다. 충돌이나 실패, 승인 대기 상태면 머지하지 않고 보고한다.
- 저장소는 merge commit을 막아 두었으므로 `gh pr merge <PR> --squash`로 머지한다. 머지 후 브랜치는 저장소 설정(`deleteBranchOnMerge`)이 지운다. 제목은 PR 제목을 그대로 쓴다.
- 머지 후 `main`으로 돌아가 `git pull --ff-only`로 맞춘다. 보호 규칙으로 머지가 막히면 `--admin` 등으로 우회하지 않고 보고한다.

## 보고

PR URL, CI 체크 결과, 실행한 검사, 건너뛴 것과 이유, 계획 완료 커밋 SHA, 파이프라인 종료 여부, 머지 결과(SHA)를 한국어로 짧게 적는다.
