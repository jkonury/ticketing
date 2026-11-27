# 개발 워크플로 로드맵 (W 트랙)

- 결정: [ADR 0048](../adr/0048-ai-dev-workflow-pipeline.md)
- 작성일: 2026-11-25
- 상태: W0~W3 완료. 다음 단계는 W4

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

- [x] [ADR 0048](../adr/0048-ai-dev-workflow-pipeline.md)
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

## W2.5. 최소 지표

- [x] Stop 게이트의 검사 결과, 검사 소요 시간, 누적 차단 횟수를 `.metrics/events.jsonl`에 추가한다. 상한을 넘어 검사를 건너뛴 경우도 기록한다.
- [x] task 전환과 파이프라인 종료 때 이전 task의 소요 시간을 같은 파일에 남긴다.
- [x] Claude Code·Codex 훅 입력으로 차단 후 통과를 재현하고 JSONL 기록을 확인한다.

**완료 기준**: 파이프라인 세션에서 검사가 실패해 Stop을 한 번 차단하고, 수정 후 통과하면 두 결과가 같은 실행과 task에 연결되어 `.metrics/`에 남는다. 기록 형식은 [워크플로 지표](./metrics/README.md)를 본다.

## W3. `/plan-next`, `/implement`, 계획 템플릿

- [x] [실행 계획 템플릿](./plans/template.md)에 범위, 선행 관계, task별 AC와 테스트 경계를 담는다.
- [x] 초안 작성과 승인 기록 절차를 [plan-next](../../.agents/skills/plan-next/SKILL.md)에 정의한다.
- [x] [implement](../../.agents/skills/implement/SKILL.md)와 CLI 검증으로 승인된 task의 구현·게이트 검사·로컬 커밋을 진행한다. 마지막에 최종 검사를 수행한다.
- [x] [core DB의 결제 스키마 정리](./plans/2026-11-27-core-payment-schema-cleanup.md)를 시범 계획으로 완주한다.

**완료 기준**: 시범 계획이 승인된 뒤 task 검증과 로컬 커밋을 거쳐 `done`에 도달한다. 검증용 DB에 마이그레이션을 적용한 상태에서 결제·환불 E2E가 통과하고, 게이트 지표가 남는다.

### 설계 입력: mattpocock/skills의 `to-spec`·`to-tickets` 검토 (2026-11-27)

두 스킬은 별도 스킬로 도입하지 않고, 아래 요소만 계획 템플릿과 `/plan-next` 설계에 흡수하기로 했다. 이슈 트래커 게시를 전제하고 스펙 문서를 새로 만드는 방식이 [ADR 0048](../adr/0048-ai-dev-workflow-pipeline.md) 결정 1·7(계획을 `docs/workflow/plans/`에 두고 `status`로 승인)과 겹치기 때문이다. 원본은 [mattpocock/skills](https://github.com/mattpocock/skills/tree/main/skills/engineering)의 `to-spec`, `to-tickets`(commit `6f7f783`)다.

계획 템플릿과 `/plan-next`에 반영할 요소:

- **task는 수직 슬라이스로 쪼갠다.** task 하나는 모든 계층을 가로지르는 얇은 경로이고, 혼자 시연하거나 검증할 수 있어야 하며, 새 컨텍스트 하나에 들어가는 크기여야 한다. 준비 리팩터링이 필요하면 먼저 둔다. `subagent-delegation.md`에 AC 작성 규칙은 있으나 task를 쪼개는 기준이 없어 이 부분을 채운다.
- **task마다 선행 task(`Blocked by`)를 적는다.** 선행이 없으면 바로 시작할 수 있는 task다. 선행이 모두 끝난 task부터 진행한다.
- **폭이 넓은 기계적 변경은 expand → migrate → contract로 나눈다.** 새 형태를 옛 형태 옆에 추가하고(expand), 호출처를 영향 범위 단위(패키지, 디렉터리)로 나눠 옮기고(migrate), 호출처가 남지 않으면 옛 형태를 지운다(contract). 각 migrate task는 expand에, contract task는 모든 migrate task에 막힌다. M6 분리 작업이 대상이다.
- **쪼갠 결과를 사람이 승인한다.** 세분도(너무 크거나 작은가), 선행 관계(정말 막는 것만 걸었는가), 합치거나 더 쪼갤 task를 사용자에게 확인하고, 승인되면 `status: approved`로 바꾼다. ADR 0048의 "사람 승인"과 같은 단계다.
- **테스트 결정을 계획에 담는다.** 테스트할 seam(가능하면 가장 높은 곳에 하나), 테스트 대상 모듈, 같은 종류의 기존 테스트(선례)를 적는다. 구현 단계의 [`tdd` 스킬](../../.agents/skills/tdd/SKILL.md)이 seam 합의를 요구하므로 계획에서 미리 정한다.
- **범위 밖(Out of Scope)을 적는다.** `docs/roadmap.md`의 "단계와 무관한 후속 항목"을 앞당기지 않는 규칙과 맞춘다.

가져오지 않는 요소: User Stories 목록, 이슈 트래커 게시와 `ready-for-agent` 라벨, `/setup-matt-pocock-skills`, "경로·코드 조각을 넣지 말 것"(이 저장소는 `check-docs.sh`가 백틱 경로를 검사한다).

착수 시 확인할 것:

- 계획 형식이 [ADR 0048](../adr/0048-ai-dev-workflow-pipeline.md) 결정 7(계획 문서 형식과 `status`)을 바꾸면 새 ADR로 남긴다. 사용자와 합의한 뒤에 쓴다.
- `/plan-next`의 앞 단계(요구 정리)는 [`grill-with-docs` 스킬](../../.agents/skills/grill-with-docs/SKILL.md)을 쓴다. 뒤 단계는 `tdd`(구현)와 `review-diff`(리뷰)다. `/implement`는 `review-diff`를 호출하지 않으며, 리뷰는 별도로 요청한다.
