# Backend 리뷰 지침 (core, core-batch, payment, payment-batch, realtime, gateway, fake-pg, common, contract)

백엔드 변경(diff, PR)을 리뷰할 때 쓰는 확인 순서와 판정 기준이다. [백엔드 공통 지침](backend.md)에서 변경 모듈의 작성 지침을 찾고, 테스트는 [백엔드 테스트 지침](backend-testing.md)을 적용한다. 설계 규칙은 [AGENTS.md 4절](../../AGENTS.md#4-반드시-지킬-설계-규칙)을 따른다. web 변경은 [frontend-review.md](frontend-review.md)를 따른다.

## 리뷰 방법

1. `git diff --stat`으로 범위를 확인하고 변경을 모듈(`core`의 어떤 모듈인지, `realtime`, `gateway` 등)과 계층(`domain`, `application`, `adapter`)으로 분류한다.
2. 아래 심각도 순서대로 확인한다. 위 단계에서 막히면 아래 단계는 지적만 짧게 남긴다.
3. 이번 변경이 건드리지 않은 기존 코드의 문제는 지적하지 않는다. 필요하면 후속 항목으로 제안한다.
4. 라이브러리 버전과 API(Spring Boot 4.x, Modulith, Spring Cloud Gateway, jOOQ)는 기억이 아니라 공식 문서로 확인한다.
5. 실행 결과를 근거로 삼는다. 테스트·검사는 `AGENTS.md` 6절의 스크립트로 실행하고, 실패는 실패한 그대로 보고한다.

## 심각도

| 등급 | 의미 | 처리 |
|---|---|---|
| 차단 | 설계 규칙 위반, 정합성·동시성 결함, 보안 결함, 데이터 손상 가능성 | 머지 전에 반드시 수정 |
| 주의 | 오류 처리·트랜잭션·테스트 누락, 규칙 이탈 | 수정 권장. 미루려면 사유 기록 |
| 제안 | 가독성, 이름, 중복 | 선택 |

지적은 `파일:행`, 해당 작성·테스트 지침의 절 또는 `AGENTS.md` 4절 번호, 구체적인 실패 시나리오를 함께 적는다. 취향 차이는 차단·주의로 올리지 않는다.

## 1. 차단 항목

작성 지침의 다음 절을 어긴 변경이다. 규칙 본문은 각 절을 따른다.

- [AGENTS.md 4절](../../AGENTS.md#4-반드시-지킬-설계-규칙) 전체. 체크 키워드: 모듈 간 직접 참조, 스키마 넘는 FK·조인, `realtime` PostgreSQL 접근, 코어의 대기열 지식, `(회차, 좌석)` 유니크가 최종 방어선(위반이 500으로 새지 않는가), 결제·웹훅 멱등
- [core 영속성](backend-core.md#영속성)·[트랜잭션](backend-core.md#트랜잭션)·[보안](backend-core.md#보안)의 스키마·마이그레이션·트랜잭션·인가 규칙. 체크 키워드: 트랜잭션 안 외부 호출, JPA·jOOQ flush 순서, 이벤트 리스너 멱등, 적용된 Flyway 파일 수정, jOOQ 생성 코드 커밋, 로그·응답의 비밀 노출
- [booking 규칙](backend-core.md#booking). 체크 키워드: Redis 선점 Lua 원자성, 소유자 비교 후 해제, DB 커밋 실패 시 선점 되돌림과 TTL 누락의 Batch 보정
- [payment 작성 지침](backend-payment.md): 웹훅 서명 검증·멱등, 결제 조회의 JWT·`role` 검증
- [realtime](backend-realtime.md)·[gateway](backend-gateway.md) 작성 지침과 [WebFlux 공통 지침](backend.md#webflux)의 블로킹 금지·인스턴스 메모리 의존 금지 규칙
- 리뷰 고유: API 계약(요청·응답, 오류 `code`)을 바꿨다면 BFF·Gateway·[integrations.md](../architecture/integrations.md)와 일치하고 호환 깨짐이 합의됐는가

## 2. 주의 항목

작성 지침의 나머지 규칙 이탈이다.

- 구조: [core 모듈 구조](backend-core.md#코어-모듈-구조-modulith), [헥사고날](backend-core.md#헥사고날-booking-coupon), [contract](backend-contract.md). 모듈 의존을 바꿨다면 Modulith 검증과 `modulithDocs` 갱신
- 코드 규칙: [Kotlin](backend.md#kotlin), [JPA 엔티티](backend-core.md#jpa-엔티티), [영속성](backend-core.md#영속성), [API](backend-core.md#api)와 [API 규약](../architecture/api-conventions.md)
- 실행 경계: [Virtual Thread](backend.md#virtual-thread), [realtime](backend-realtime.md), [gateway](backend-gateway.md), [로깅](backend.md#로깅)
- 배치·결제: [Batch Job](backend-core.md#batch-job)([ADR 0019](../adr/0019-batch-jobs-in-owning-modules.md)), [core-batch](backend-core-batch.md), [payment-batch](backend-payment-batch.md), [payment](backend-payment.md)
- 테스트: [백엔드 테스트 지침](backend-testing.md) 전체
- 리뷰 고유: 변경한 동작에 맞는 테스트가 있는가(경쟁이 있는 변경은 실제 병렬 실행으로 불변식 단언). 테스트를 지우거나 `@Disabled`로 통과시키지 않았는가

## 3. 제안 항목

[백엔드 공통 지침의 가독성](backend.md#가독성), 중복(`XxxFixture`), "왜"만 적는 주석, [빌드 지침](backend.md#빌드)(`@Suppress`·baseline 무합의 추가 금지)에 해당하는 사항이다.

## 4. 문서 정합

- 설계와 다르게 구현했다면 `docs/architecture.md` 변경 합의가 있었는가. 문서와 코드가 어긋난 채 남지 않았는가.
- 설계를 바꾸는 결정이면 새 ADR이 있는가.
- 단계 항목을 끝낸 변경이면 `docs/roadmap.md` 체크박스가 갱신됐는가. "단계와 무관한 후속 항목"을 요청 없이 앞당겨 구현하지 않았는가.
- 명령, 포트, 환경 변수가 바뀌면 `AGENTS.md`와 관련 문서가 따라왔는가.

## 5. 검증 확인

- `./scripts/agent/test/run-tests.sh`가 필터 없이 통과했는가.
- Kotlin 변경 후 `ktlintCheck detekt`를 통과했는가.
- 모듈 의존을 바꿨다면 Modulith 구조 검증을 돌렸는가.
- 사용자가 로컬 백엔드 E2E를 요청했다면 `./scripts/e2e/run.sh`의 결과를 확인했는가.
- 성능에 민감한 변경(좌석 선점, 대기열, 부하 경로)이면 `scripts/loadtest/run.sh` 결과를 확인했는가. 다른 Gradle 작업과 동시에 돌리지 않았는가.
- 문서를 옮기거나 제목·링크를 바꿨다면 `./scripts/agent/docs/check-docs.sh`를 통과했는가.

## 리뷰 결과 형식

```
## 요약
변경 범위와 전반적 판단 한 줄.

## 차단
- `core/src/...:행` (해당 지침의 파일·절 또는 AGENTS.md 4절 N번): 문제와 실패 시나리오. 제안 수정.

## 주의
- ...

## 제안
- ...

## 검증
- run-tests: 통과/실패(실패한 테스트 이름과 메시지 일부)
- 실행하지 않은 검사와 사유
```

지적이 없는 등급은 "없음"으로 적는다. 확인하지 못한 항목은 통과로 쓰지 않고 "확인 안 함"으로 적는다.

delegate 스킬로 위임한 리뷰는 [delegate 워크플로 §5](../../.agents/skills/delegate/references/workflow.md#5-지시서-템플릿)의 출력 형식으로 쓰고, 이 문서의 등급은 차단 → 차단 사항, 주의·제안 → 비차단 사항으로 옮긴다.
