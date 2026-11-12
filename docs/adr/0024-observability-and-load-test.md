# 0024. 관측 스택과 부하 테스트 방식

- 상태: 수락
- 작성일: 2026-11-12
- 합의일: 2026-11-12
- 결정 주체: 사용자
- 대체: 없음
- 대체됨·일부 변경: 없음
- 관련: [설계](../architecture.md), [ADR 0013](0013-jwt-behind-bff.md), [ADR 0014](0014-sse-bypasses-bff.md)

## 배경

M5에서 대기열·좌석 선점·결제 흐름에 1만 명 규모의 부하를 걸어 기준 수치를 얻어야 한다. 기준이 없으면 병목을 개선해도 효과를 비교할 수 없다. 측정에 필요한 것은 세 가지다. 앱 내부 지표와 요청 단위 추적을 보는 관측 수단, 1만 개 SSE 연결을 포함한 부하를 만드는 도구, 테스트 데이터와 인증을 준비하는 방법이다.

제약은 다음과 같다. 측정은 단일 개발 머신에서 한다. 컨테이너 이미지와 k8s는 M6 범위다. web BFF는 측정 대상이 아니다. 개선 내용은 기준 측정 결과를 봐야 정할 수 있으므로, 이 결정은 측정 방법까지만 다루고 개선은 M5-2로 넘긴다.

## 결정

1. 추적은 Micrometer Tracing과 OpenTelemetry 브리지로 계측하고, OTLP(HTTP)로 Grafana Tempo에 보낸다. 지표는 Micrometer와 Prometheus registry로 `/actuator/prometheus`에 노출하고, Grafana에서 Prometheus exemplar로 trace와 연결한다.
2. 부하는 k6가 Gateway(8000)를 직접 호출한다. web BFF는 거치지 않는다.
3. 부하 테스트용 회원 1만 명은 SQL 시드로 만든다. k6가 로컬 `JWT_SECRET`으로 HS256 access 토큰을 직접 서명한다(클레임 `sub`, `role`, `iat`, `exp`). 로그인(bcrypt)은 부하에 넣지 않는다.
4. SSE 부하는 xk6-sse 확장을 넣은 k6 바이너리로 한다. Docker `grafana/xk6`로 빌드하고 산출물은 `build/loadtest/k6`에 둔다.
5. 시나리오는 두 개다. `queue-surge`는 1만 명이 대기열 등록부터 예매 결제·확정까지 전체 흐름을 밟는다. `hot-seats`는 1,000명이 같은 시각에 앞줄 100석을 놓고 선점을 시도한다. 기준 측정은 fake-pg 실패율 0%로 하고, 실패 주입은 별도 1회로 한다.
6. 관측 스택은 `compose.observability.yaml`로 Prometheus, Tempo, Grafana를 띄운다. Prometheus는 remote-write를 받아 k6 지표도 함께 저장한다. Grafana는 3001 포트를 쓴다(3000은 web). datasource와 대시보드는 저장소에 프로비저닝한다. 로그 수집(Loki)은 하지 않는다.
7. 실행 환경은 호스트 `java -jar`와 `taskset`이다. postgres와 redis만 Compose로 띄우고, core·realtime·gateway·fake-pg는 고정 힙으로 실행해 CPU 코어를 k6와 나눈다. 측정은 3회 반복해 중앙값을 기록하고 하드웨어와 버전을 함께 남긴다.
8. 합격 기준(잠정)은 예매 생성 p95 300ms 미만, 예상 응답(409 좌석 경합, 429 rate limit)을 뺀 오류율 1% 미만, SSE 연결 성공률 99.9% 초과다. 중복 판매 0건은 절대 기준이다.
9. phase는 둘로 나눈다. M5-1은 관측과 기준 측정, M5-2는 병목 개선과 전후 비교다.

## 검토한 대안

| 결정 | 검토한 대안 | 선택 이유 |
| --- | --- | --- |
| 추적: Micrometer Tracing + OTel 브리지 → OTLP → Tempo | Brave + Zipkin, OTel + Jaeger | Grafana 하나에서 지표(exemplar)와 trace를 연결한다. OTLP는 표준이라 M6 k8s에서도 그대로 쓴다 |
| 부하 경로: k6 → Gateway 직접 | web BFF 경유 | BFF를 거치면 1만 개 세션 로그인과 Next 서버 비용이 측정에 섞여 원인 분석이 어려워진다. 측정 대상은 대기열·realtime·core다 |
| 인증: k6가 테스트 전용으로 JWT를 직접 서명 | 실제 로그인 API 1만 회 | bcrypt 로그인 1만 회가 준비 시간과 부하를 차지한다. 로컬 `JWT_SECRET`은 부하 테스트 환경에서만 쓴다 |
| SSE: xk6-sse 커스텀 빌드 | 상태 조회 폴링, 일부만 SSE | 1만 개 SSE 동시 연결 유지가 WebFlux realtime 검증의 핵심이다. 폴링은 rate limit(초당 20)과도 겹친다 |
| phase 분할: M5-1(관측·기준 측정), M5-2(개선) | 한 phase | 개선 내용은 기준 측정 결과를 봐야 정할 수 있다 |
| 실행 환경: 호스트 `java -jar` + `taskset` | 컨테이너 CPU 제한 | 컨테이너 이미지는 M6 범위다. CPU 분리로 k6와 앱의 경합을 줄이고 하드웨어를 문서에 남긴다 |

## 결과

- 얻는 것: 기준 수치와 병목 후보를 같은 방법으로 반복 측정할 수 있다. 지표와 trace를 한 화면에서 연결해 원인을 좁힐 수 있다. 부하 방식이 M6 k8s 전환 때 그대로 재사용된다.
- 감수하는 것: 측정은 단일 머신 기준이라 절대값보다 개선 전후 비교에 의미가 있다. 테스트 전용 JWT 서명은 운영 경로가 아니며, 운영에서는 `JWT_SECRET`을 부하 테스트 용도로 쓰지 않는다. 로그 수집(Loki)과 web 계측은 하지 않으므로 web 구간의 지연은 이 측정에 잡히지 않는다. 실패 주입 1회는 기준 측정과 분리해 해석한다.
- 바뀌는 문서·지침: `docs/architecture.md § 11`(부하 테스트·관측 항목). `docs/roadmap.md`와 AGENTS.md § 6은 M5-1 마지막 step에서 갱신한다.

## 검증

- 중복 판매 0건: 모든 부하 실행 뒤 정합성 검증 스크립트가 `(회차, 좌석)` 유니크 제약 위반과 확정 좌석 수를 대조한다(M5-1 step 4·5에서 추가).
- 시나리오 실행: 실행 스크립트가 k6 바이너리(`build/loadtest/k6`)와 시나리오를 호출하고, 결과는 성능 측정 문서에 기록한다(M5-1 step 5·7에서 추가).
- 합격 기준: k6 thresholds로 p95·오류율·SSE 연결 성공률을 검사한다(M5-1 step 6에서 추가).

## 변경 이력

- 2026-11-12: 작성, 합의
