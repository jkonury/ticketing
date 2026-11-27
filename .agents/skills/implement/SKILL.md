---
name: implement
description: 승인된 실행 계획을 task마다 구현·검증·로컬 커밋한다. Claude Code의 /implement와 Codex의 $implement에서 쓴다.
disable-model-invocation: true
---

1. [실행 계획 지침](../../../docs/workflow/plans/README.md)을 읽고 지정된 계획의 승인 상태와 작업 트리를 확인한다. 계획과 실제 코드가 어긋나면 사용자와 범위를 다시 확인한다.
2. 새 실행에는 현재 HEAD의 SHA를 구해 저장소 루트에서 Bash로 `./harness/bin/pipeline.py start --plan <계획 경로> --base <SHA>`를 실행한다. 같은 계획을 이어 가면 `resume`을 쓴다. `status`로 현재 계획·task, 리뷰 기준 SHA와 세션 바인딩을 확인한다.
3. 선행 task가 끝난 첫 미완료 task를 고르고, `./harness/bin/pipeline.py task <id> --ac '<명령>'`으로 등록한다. 여러 명령은 `--ac`를 반복한다. 아직 커밋하지 않은 동일 task를 재개할 때는 등록 상태와 차단 횟수를 이어 쓴다. 합의된 테스트 경계에서 [tdd](../tdd/SKILL.md)로 구현한다.
4. 계획의 task 체크박스를 갱신하고 `./harness/bin/pipeline.py verify --config scripts/agent/gate-rules.toml`로 커밋 전에 검증한다. 실패를 고친 뒤 다시 검증한다. 차단 상한 또는 해결하기 어려운 검사 실패는 상태와 로그를 사용자에게 보고한다. 검증 성공 후 계획의 체크박스 변경과 task 범위만 스테이지하고 저장소 지침에 따라 로컬 커밋한다.
5. 모든 task가 끝나면 계획의 최종 검사와 E2E를 실행하고 [review-diff](../review-diff/SKILL.md)로 시작점과 HEAD를 검토한다. 수정이 필요하면 적용 범위를 확인하고 관련 검증을 다시 한다. 최종 검증과 리뷰를 마친 뒤 계획 상태를 `done`으로 바꾸고 `./harness/bin/pipeline.py end`를 실행한다.
