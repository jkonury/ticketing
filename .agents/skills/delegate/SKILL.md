---
name: delegate
description: herdr 워크플로로 구현자·리뷰어 에이전트에게 task를 위임한다. 사용자가 herdr 위임을 명시적으로 지시할 때만 사용.
disable-model-invocation: true
---

herdr 오케스트레이션 위임을 시작한다. 사용자가 명시적으로 지시한 경우에만 쓴다.

이 스킬은 프로젝트에 묶이지 않는다. 문서와 스크립트는 모두 이 스킬 디렉터리(`SKILL_DIR`, 이 파일이 있는 곳) 안에 있다. 경로는 `SKILL_DIR` 기준이고, 스크립트는 대상 저장소 루트에서 실행한다.

1. `references/herdr.md`(명령 정리)와 `references/workflow.md`(절차)를 읽는다. 검색하지 않는다.
2. 대상 저장소의 프로젝트 지침(`AGENTS.md`·`CLAUDE.md`와 그 지침이 가리키는 문서)에서 워크플로 머리말에 적힌 프로젝트 값을 확인한다. task 단위, 커밋 규칙, 검증 명령, 리뷰 기준이 여기에 해당한다.
3. 위임 범위를 확인한다: 워크플로 2절 "실행 범위"와 인자(`$ARGUMENTS`)로 받은 계획·task.
   - 구현자와 리뷰어는 서로 다른 종류의 에이전트로 지정한다.
   - 계획이 모호하면 task 분해 전에 사용자에게 묻는다.
4. 워크플로 3절 "준비"를 수행한다(`SKILL_DIR/scripts/herdr-up.sh`).
5. task별로 4절 루프를 따른다.
   - 5절 템플릿으로 지시서를 쓰고 `scripts/herdr-delegate.sh`로 위임, `scripts/herdr-wait.sh`로 대기한다.
   - 위임 전 Git 검사, task별 새 세션, 컨텍스트·5시간 한도 확인(`scripts/herdr-context.sh`, `scripts/herdr-limit.sh`)을 지킨다.
   - 서브에이전트가 설계 변경 필요를 보고하면 진행을 멈추고 사용자에게 알린다.
   - 결정 근거로 쓰는 보고 내용은 해당 파일을 직접 열어 확인한다.
6. task가 끝날 때마다 커밋한다(워크플로 지시는 task별 커밋 요청으로 본다). 커밋 메시지 형식은 프로젝트 지침을 따른다.
7. 끝나면 `scripts/herdr-down.sh`로 정리한다. 프로젝트에 로드맵이 있으면 체크박스를 갱신한 뒤 결과를 요약해 보고한다.
