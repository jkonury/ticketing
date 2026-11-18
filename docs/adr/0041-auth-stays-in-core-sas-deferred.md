# ADR 0041: M6에서는 인증 구조를 유지하고 Spring Authorization Server 도입은 후속으로 미룬다

- 상태: 합의
- 작성일: 2026-11-18
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [ADR 0020](0020-gateway-verification-contracts.md), [ADR 0022](0022-bff-session-and-aggregation-apis.md), [ADR 0034](0034-platform-admin-role-and-audit.md), [M6 계획](../plans/m6-msa.md)

## 맥락

현재 core `member`가 JWT를 발급하고 Gateway가 검증하며, BFF가 토큰을 서버 측에만 보관한다. 로드맵은 M6에서 Spring Authorization Server(SAS) 도입 여부를 재검토하라고 한다.

공식 자료로 확인한 사실은 다음과 같다.

- SAS는 Spring Security 7.0에 통합됐고, 독립 프로젝트 1.5.x는 2026년 6월에 OSS 지원이 끝났다([공지](https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0)).
- 좌표는 `org.springframework.security:spring-security-oauth2-authorization-server`이고 Boot 4 스타터 이름은 `spring-boot-starter-security-oauth2-authorization-server`다. 스타터 이름은 [InfoQ 기사](https://infoq.com/articles/spring-security-7-server-side-dpop-token-exchange)에서만 확인했으므로 도입 시 릴리스 노트로 다시 확인한다.

## 검토한 대안

- (A) 현행 유지. payment·settlement는 Gateway가 검증한 요청만 받는다.
- (B) `member`를 분리해 SAS로 대체한다. Gateway는 리소스 서버로 검증한다.
- (C) M6에서는 A로 두고 서비스 간 검증용 JWKS 공개 키 방식만 정리한다. SAS는 후속 ADR로 다룬다.

| 안 | 장점 | 단점 |
|---|---|---|
| A | 변경이 없다 | 서비스가 늘 때 검증 키 배포 방식이 불명확하다 |
| B | MSA에 표준적이고 포트폴리오 가치가 크다 | 로그인·refresh·BFF 보관·SSE 티켓·역할 변경 시 refresh 폐기 흐름을 모두 다시 만들어야 해 M6 범위가 크게 늘어난다 |
| C | 위험이 작고 SAS 도입 경로가 열린다 | SAS 도입이 뒤로 미뤄진다 |

## 결정

**(C)**. M6에서는 `member`의 JWT 발급, Gateway 검증, BFF 서버 측 보관을 유지한다.

- payment·settlement는 사용자 요청을 직접 받지 않거나 Gateway를 거쳐 받는다. 서비스가 JWT를 직접 검증해야 하면 Gateway와 같은 공개 키 검증 방식(JWKS 또는 공개 키 설정)을 쓴다. 구체 방식은 payment 분리 단계에서 정한다.
- 서비스 간 비동기 통신은 Kafka 이벤트로 하므로 서비스 간 토큰 전파는 필요하지 않다.
- SAS 도입은 M6 완료 뒤 새 ADR로 다룬다. 로드맵 후속 항목 표의 SAS 항목은 시점을 "M6 완료 후"로 고친다.

## 결과

- 얻는 것: M6 범위 집중, 기존 인증 흐름의 회귀 위험 없음.
- 감수하는 것: OAuth2/OIDC 학습 항목이 뒤로 밀린다.
- 바뀌는 문서·지침: `docs/roadmap.md`의 M6 항목과 후속 항목 표.

## 변경 이력

- 2026-11-18: 작성(합의)
