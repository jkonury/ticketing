# 개발 워크플로 로드맵 (W 트랙)

- 결정: [ADR 0047](../adr/0047-ai-dev-workflow-pipeline.md)
- 작성일: 2026-11-25
- 상태: W0~W2 완료. 다음은 W2.5

Claude Code·Codex 공용 반자율 개발 파이프라인을 만드는 트랙이다. 서비스 로드맵 [docs/roadmap.md](../roadmap.md)의 M6와 병행하고, M6 task를 파이프라인의 시범 대상으로 쓴다. 각 단계는 **완료 기준**을 만족해야 다음 단계로 넘어간다.

## 단계 요약

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| W0 | 결정 기록과 지침 갱신 | 문서 검사 통과 |
| W1 | 스킬 단일 원본 | 두 도구에서 같은 스킬을 명시적으로 호출 |
| W2 | 훅 어댑터와 Stop 게이트 | 고의 실패를 두 도구의 게이트가 막고 에이전트가 고침 |
| W2.5 | 최소 지표(시간, 게이트 반복 횟수) | 게이트 이벤트가 `.metrics/`에 남음 |
| W3 | `/plan-next`, `/implement`, 계획 템플릿 | payment 분리의 남은 하위 범위를 파이프라인으로 완주 |
| W4 | 토큰 수집과 집계 리포트, 두 도구 비교 | W3 완주 건의 리포트가 `docs/workflow/metrics/`에 있음 |
| W5 | PR 자동 리뷰(CI) | PR에 리뷰 코멘트 게시. 세부 결정은 착수 시 합의 |
| W6 | 범용 추출 검토 | 추출 여부 ADR |

## W0. 결정 기록과 지침 갱신

- [x] [ADR 0047](../adr/0047-ai-dev-workflow-pipeline.md)
- [x] 이 로드맵 문서
- [x] `AGENTS.md`: 문서 목록, W 트랙 병행, 승인된 계획의 커밋 예외

**완료 기준**: `./scripts/agent/docs/check-docs.sh`가 통과한다.

## W1. 스킬 단일 원본

- [x] `.claude/skills/{delegate,handoff}`를 `.agents/skills/`로 옮기고 `.claude/skills`를 `../.agents/skills`를 가리키는 심볼릭 링크로 바꾼다
- [x] 스킬별 `agents/openai.yaml`에 `allow_implicit_invocation: false`
- [x] Codex가 Claude 전용 frontmatter(`disable-model-invocation`)를 허용하는지 확인: 무시하고 로드한다(codex-cli 0.171.0)
- [x] 스킬 자기완결화: 스킬이 쓰는 문서·스크립트를 스킬 디렉터리(`references/`, `scripts/`)로 옮겨 전역 스킬 디렉터리로 옮겨도 동작하게 한다. 프로젝트 값은 프로젝트 지침([위임 값](../rules/subagent-delegation.md#이-저장소의-위임-값))에서 읽는다

**완료 기준**: Claude Code(`/<skill>`)와 Codex(`$<skill>`)에서 같은 스킬을 명시적으로 호출해 본문이 로드된다. (2026-11-25 임시 스킬로 두 도구 모두 확인)

## W2. 훅 어댑터와 Stop 게이트

- [x] `harness/lib/hook_input.py`: 두 도구의 훅 입력 정규화
- [x] `harness/hooks/stop_gate.py`: 파이프라인 세션 판별, 변경 경로별 검사 선택, task AC 우선, 반복 상한 3회
- [x] `harness/bin/pipeline.py`: 파이프라인 상태 파일(`.metrics/active.json`) 시작·task 전환·종료
- [x] `.claude/settings.json`, `.codex/hooks.json`에 Stop 훅과 세션 바인딩 훅(PostToolUse, `harness/hooks/bind_session.py`) 등록. 경로 규칙은 `scripts/agent/gate-rules.toml`
- [x] `harness/tests/` 단위 테스트
- [x] Claude Code 실동작 확인: 고의 ktlint 위반을 1회 차단하고 수정한 뒤 통과(2026-11-25)
- [x] Codex 실동작 확인: 훅 신뢰 승인 후 같은 절차로 2회 차단(형식 수정 → 식 본문 규칙)하고 수정한 뒤 통과(2026-11-25)

**완료 기준**: 파이프라인 상태를 켠 세션에서 고의 ktlint 위반을 남기고 종료하려 하면 게이트가 막는다. 에이전트가 이를 고친 뒤 종료한다. 이 동작을 두 도구에서 각각 확인한다. 파이프라인 상태가 없는 세션에서는 게이트가 동작하지 않는다.

참고:

- Codex 프로젝트 훅은 훅마다 해시로 신뢰를 기록한다. `.codex/hooks.json`을 바꾸면 Codex 대화형 세션의 `/hooks`에서 다시 승인해야 동작한다.
- 게이트는 커밋하지 않은 변경 전체를 검사한다. 파이프라인은 깨끗한 작업 트리에서 시작해야 한다(W3 `/implement`의 시작 조건).
