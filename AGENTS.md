# ticketing 작업 지침

## 1. 작업별 참조

작업 경로와 유형에 해당하는 지침을 먼저 읽는다. 설계 근거가 필요하면 [아키텍처](docs/architecture.md)와 관련 [ADR](docs/adr/README.md)을 확인한다.

- 백엔드 변경은 [백엔드 공통·모듈별 지침](docs/rules/backend.md), 테스트·E2E·부하·CI 작업은 [백엔드 테스트 지침](docs/rules/backend-testing.md)을 따른다.
- `web` 변경은 [프런트엔드 지침](docs/rules/frontend.md)을 따른다.
- 리뷰와 위임은 해당 작업의 지침을 따른다. [백엔드 리뷰](docs/rules/backend-review.md) · [프런트엔드 리뷰](docs/rules/frontend-review.md) · [서브에이전트 위임](docs/rules/subagent-delegation.md)
- 기준점과 HEAD 사이 변경의 Standards·Spec 리뷰 요청은 [review-diff 스킬](.agents/skills/review-diff/SKILL.md)을 따른다.

## 3. 작업 방식

- 로드맵 항목을 계획하거나 구현할 때는 해당 트랙의 현재 단계와 완료 기준을 확인한다. 트랙 안에서 단계 순서를 지키고, 끝낸 항목은 해당 로드맵에 표시한다. 보류한 항목을 요청 없이 앞당기지 않는다. [구현](docs/roadmap.md) · [AI 워크플로](docs/workflow/roadmap.md) · [UI](docs/ui/roadmap.md)
- 설계 결정을 바꿀 때는 사용자와 먼저 합의하고 [아키텍처](docs/architecture.md)와 [ADR](docs/adr/README.md)을 갱신한다.
- 라이브러리 API는 프로젝트가 사용하는 버전의 공식 문서로 확인한다.

## 4. 반드시 지킬 설계 규칙

1. 코어 모듈 간 호출은 Modulith 이벤트로만 한다. 다른 모듈의 내부 클래스를 직접 참조하지 않는다.
2. 모듈별 PostgreSQL 스키마를 넘는 외래 키와 조인을 만들지 않는다.
3. `realtime`은 PostgreSQL에 접근하지 않는다.
4. 코어는 대기열을 모른다. 입장 토큰은 Gateway에서 검증한다.
5. JWT는 BFF 서버 측에만 둔다.
6. 좌석 확정의 최종 방어선은 DB의 `(회차, 좌석)` 유니크 제약이다.
7. 결제 요청과 웹훅은 멱등 키로 중복을 거른다.

## 5. 명령

저장소 지도와 최초 실행은 [온보딩](docs/onboarding.md), 기동·운영 절차는 [런북](docs/runbook.md)을 본다.

## 6. 테스트와 검사

- 완료 전에 저장소 루트에서 `./scripts/agent/test/verify-changed.sh`를 실행한다. JVM 변경의 최종 테스트는 `./scripts/agent/test/run-tests.sh`를 필터 없이 실행한다. jOOQ 코드 생성과 Testcontainers에는 Docker가 필요하다.
- 같은 작업 디렉터리의 Gradle 실행은 순차로 진행한다. 코어 모듈 의존을 바꾸면 [Modulith 검증·다이어그램 갱신 지침](docs/rules/backend-core.md#코어-모듈-구조-modulith)을 따른다.
- 전체 E2E는 PR의 CI에서 확인한다. 로컬 E2E는 명시적 요청이 있을 때 실행한다. [실행 조건](docs/rules/backend-testing.md#e2e부하ci-실행)
- 실패한 검사와 메시지를 보고한다. 테스트 삭제·비활성화나 `--no-verify`로 실패를 우회하지 않는다. `@Suppress`와 baseline 추가는 사용자와 합의한다.

## 7. 공통 규칙

- 대화·문서·커밋 메시지는 한국어로 쓰고, 코드 식별자는 영어로 쓴다. 주석은 코드에서 드러나지 않는 이유를 설명할 때 쓴다.
- 비밀 값은 저장소에 넣지 않는다.
- 커밋은 요청받은 범위에서만 한다. 승인된 계획이나 명시적 herdr 위임은 해당 task의 로컬 커밋 요청으로 본다. [계획 승인](docs/workflow/plans/README.md#실행과-재개) · [herdr 위임](.agents/skills/delegate/references/workflow.md#2-실행-범위)
- 커밋 메시지는 `<type>(<scope>): <요약>` 형식을 쓴다. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore` 중 하나이며 scope는 모듈명이다. 도구 서명은 넣지 않는다. PR은 [템플릿](.github/pull_request_template.md)을 따른다.
