# ADR 0047: HTTP API 계약의 OpenAPI 스펙 추출과 BFF 경계 zod 스키마 생성

- 상태: 제안
- 작성일: 2026-11-25
- 합의일: 미합의
- 결정 주체: 사용자 요청 반영(코드 생성 도구 활용). 세부 결정은 합의 대기
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [로드맵 M6](../roadmap.md#m6-msa--k8s), [BFF 설계](../architecture/integrations.md#8-bff-nextjs), [프론트엔드 지침](../rules/frontend.md), [ADR 0042](0042-contract-module-keeps-packages-and-json-compat-rules.md)

## 배경

web BFF는 Gateway를 거쳐 core·payment·realtime의 HTTP API를 호출하고, 응답을 경계에서 zod로 검증한다(`web/src/server/api/`, `gateway-client.ts`의 `schema` 인자). 이 zod 스키마는 백엔드 응답 DTO를 보고 사람이 손으로 맞춘다. 그래서 다음 문제가 생긴다.

- 백엔드 DTO의 필드 이름·nullable 여부가 바뀌어도 컴파일·테스트가 실패하지 않는다. 불일치는 런타임에 `BAD_UPSTREAM_RESPONSE`(502)로만 드러난다.
- 에이전트가 백엔드 API를 바꿀 때마다 web 스키마 동기화를 기억해야 한다. 체크리스트 skill로 보완하는 안을 검토했지만, 검증을 결정론적 검사로 옮기는 편이 확실하다.
- M6에서 payment가 별도 앱으로 분리되면 API 소유 앱이 늘어 수동 동기화 지점도 늘어난다.

프로세스 간 이벤트 계약은 이미 `contract` 모듈과 골든 파일로 고정했다(ADR 0042). HTTP API에는 대응하는 장치가 없다. Gateway는 경로를 바꾸지 않고 하위 앱으로 전달하므로(`GatewayRoutes`), 각 앱의 스펙 경로가 BFF가 호출하는 경로와 같다.

## 결정

1. HTTP API 계약의 원본은 백엔드 코드다. 각 앱이 springdoc-openapi로 OpenAPI 3 스펙을 만든다. 대상은 core, payment, realtime의 JSON API다. fake-pg와 Gateway 자체는 대상이 아니다.
2. springdoc는 스펙만 만드는 starter(`springdoc-openapi-starter-webmvc-api`, realtime은 `-webflux-api`)를 쓰고 UI는 넣지 않는다. 버전은 프로젝트의 Spring Boot 버전 라인을 지원하는 springdoc 릴리스를 공식 릴리스 노트로 확인해 정한다. `/v3/api-docs`는 테스트와 로컬 프로필에서만 켜고, Gateway 라우트로 외부에 노출하지 않는다.
3. 스펙 추출은 앱을 따로 기동하는 Gradle 플러그인(`generateOpenApiDocs`) 대신, 각 앱의 기존 Spring 통합 테스트 컨텍스트에서 `/v3/api-docs`를 호출해 얻는다. 결과는 키를 정렬한 JSON으로 `docs/api/openapi/<앱>.json`에 커밋한다.
4. 스펙 추출 테스트는 커밋된 스펙과 다르면 실패한다. 갱신은 명시적인 갱신 옵션으로만 한다. 이벤트 계약의 골든 파일과 같은 방식이다.
5. web은 커밋된 스펙에서 BFF 경계용 zod 스키마와 타입을 생성해 `web/src/server/api/generated/`에 커밋한다. 생성은 `pnpm` 스크립트 하나로 실행하고, 생성물은 손으로 고치지 않는다.
6. 생성 zod는 `web/src/server/api/`의 응답·요청 검증에만 쓴다. 조합 결과와 화면용 타입은 지금처럼 기능 디렉터리에서 직접 정의한다([프론트엔드 지침](../rules/frontend.md)의 조합 규칙 유지).
7. 생성기는 구현 첫 단계의 스파이크로 정한다. 후보는 `@hey-api/openapi-ts`(zod 플러그인)를 먼저, 그다음 `orval`이다. 채택 조건은 세 가지다. web이 쓰는 zod 메이저 버전으로 출력할 것, Kotlin non-null 필드가 `required`로, nullable 필드가 nullable로 표현될 것, 설정 파일 기반 CLI로 같은 입력에서 같은 출력을 낼 것. 두 후보가 모두 조건을 못 맞추면 타입만 생성(`openapi-typescript`)하고 기존 수기 zod에 타입 일치 검사를 붙이는 방식으로 줄인다. 스파이크 결과와 선택은 이 ADR의 변경 이력에 적는다.
8. drift 차단은 두 곳에서 한다. 백엔드는 결정 4의 테스트가 `build`에서 실패한다. web은 CI가 생성 스크립트를 다시 실행한 뒤 `git diff --exit-code`로 생성물 차이를 검사한다. 로컬에서는 `scripts/agent/test/verify-changed.sh`가 같은 검사를 고른다.
9. realtime의 SSE 이벤트 본문은 OpenAPI 대상에서 빼고 [이벤트 카탈로그 §4](../architecture/events.md#4-sse-이벤트-realtime--브라우저)를 원본으로 둔다. 해당 zod는 수기로 유지한다.
10. 구현은 M6 `payment` 분리가 끝난 뒤 시작한다. 분리 전에 core 스펙을 만들면 payment API를 다시 옮겨야 하기 때문이다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 현행 유지(수기 zod) + 동기화 체크리스트 skill | 동기화가 에이전트의 기억에 달려 있다. 불일치가 런타임 502로만 드러난다 |
| springdoc Gradle 플러그인으로 빌드 중 앱 기동 | 앱 기동에 PostgreSQL·Kafka·Redis가 필요해 빌드가 무거워진다. 기존 통합 테스트 컨텍스트(Testcontainers 재사용)로 같은 결과를 얻을 수 있다 |
| 스펙을 손으로 먼저 쓰고 서버·클라이언트 모두 생성(API-first) | 이미 구현된 컨트롤러가 많고 1인 프로젝트에서 이중 원본이 된다 |
| 스펙·생성물을 커밋하지 않고 빌드마다 생성 | 리뷰에서 계약 변경이 보이지 않는다. web 빌드가 백엔드 빌드에 의존하게 된다 |
| 타입만 생성(`openapi-typescript`) | 런타임 경계 검증(zod)을 계속 손으로 맞춰야 한다. 결정 7의 폴백으로만 둔다 |
| tRPC·GraphQL 등 계약 방식 전환 | Gateway·BFF 구조와 기존 REST API를 모두 바꿔야 한다 |
| `openapi-zod-client` | 저장소 README에 유지보수 중단이 명시되어 있다 |

## 결과

- 얻는 것: 백엔드 DTO 변경이 스펙 diff로 리뷰에 보이고, web 스키마와의 불일치가 빌드·CI에서 실패한다. BFF 경계 스키마를 손으로 쓰는 작업이 줄어든다.
- 감수하는 것: core·payment·realtime에 springdoc 의존성이, web에 생성기 개발 의존성이 추가된다. springdoc의 Kotlin nullable 처리와 생성기의 zod 버전 지원이 맞지 않으면 커스터마이저나 폴백(결정 7)이 필요하다. 생성 스키마가 응답을 엄격하게 검증하면서 기존 수기 스키마가 숨기던 불일치가 처음 드러날 수 있다.
- 바뀌는 문서·지침: 이 ADR은 제안 상태라 아직 고치지 않는다. 합의·구현 시 같은 변경에서 `docs/architecture/integrations.md` §8(BFF 경계 검증), `docs/rules/frontend.md`(생성 스키마 사용·수정 금지), `docs/rules/backend-core.md` API 절(스펙 갱신 절차), `docs/rules/backend-testing.md`(스펙 추출 테스트), `.github/workflows/ci.yml`(web 생성물 diff), `scripts/agent/test/verify-changed.sh`를 고친다.

## 검증

- 앱별 스펙 추출 테스트: 커밋된 `docs/api/openapi/<앱>.json`과 다르면 `./gradlew build`가 실패한다.
- CI `web` job: 생성 스크립트를 다시 실행한 뒤 `git diff --exit-code -- web/src/server/api/generated`.
- 로컬: `./scripts/agent/test/verify-changed.sh`가 컨트롤러·DTO 또는 스펙 변경에서 위 두 검사를 고른다.
- 결정 2: `/v3/api-docs`가 Gateway를 통해 노출되지 않는지 Gateway 라우트 테스트로 확인한다.

## 변경 이력

- 2026-11-25: 작성(제안)
