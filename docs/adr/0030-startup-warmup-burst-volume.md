# 0030. 내부 예열의 HTTP 호출량과 동시성을 외부 예열 기준으로 확대

## 상태

수락 (2026-11-16). 사용자가 기존 구현 커밋 이후 내부 예열 강도를 외부 예열 수준으로 높이도록 요청했다. [ADR 0029](0029-layered-startup-warmup.md)의 Gateway 호출량·시간 상한·경로 제한을 대체하며 나머지 격리 정책은 유지한다.

## 맥락

[첫 내부 예열 검증](../performance/m5/15-layered-startup-warmup.md)에서 Gateway 실제 경유 8회·core 직접 호출 200회만으로는 JFR core Interpreted 표본이 31.8%로 남았다. 외부 예열의 예매 요청량은 15회 × 1,000건이며, HTTP·보안·직렬화·실제 어댑터를 반복 통과하는 양이 크게 달랐다.

## 검토한 대안

- 메모리 반복만 증가: 실제 HTTP·보안 경로와 자원 획득을 충분히 반복하지 못한다.
- core 직접 호출만 증가: Gateway·core HTTP 경유량 차이가 남는다.
- 기존 격리된 HTTP 경로를 회차별 병렬 호출한다. 이 방식을 선택한다.

## 결정

- Gateway의 기본 실제 HTTP 예열은 15회 × 1,000건, 요청 동시성 상한 1,000이다. 각 회차가 끝난 뒤 다음 회차를 시작하며 5%는 실제 JPA flush·롤백, 95%는 실제 선점 충돌·멱등 조회를 수행한다. 기존 요청별 50ms 지연은 제거한다.
- 첫 확대 진단에서 요청 본문 변환·검증 코드의 Interpreted 표본이 남아 HTTP 경로도 보강했다. Gateway는 운영 Admission 필터의 본문 캐시·회차 파서를 공유하고, core는 실제 예매 `CreateBookingRequest`를 JSON 요청 본문으로 받아 MVC 변환·Bean Validation·헤더 검증을 거친다. 본문은 고정된 양수 템플릿만 허용하고 실제 데이터 ID는 기존처럼 서버가 음수로 생성한다. 예열용 대기열·입장 토큰 Redis 쓰기는 추가하지 않는다.
- `STARTUP_WARMUP_ROUNDS`(1~30), `STARTUP_WARMUP_BATCH_SIZE`(1~1,000), `STARTUP_WARMUP_CONCURRENCY`(1~1,000)로 조절한다. core의 메모리 10,000회·직접 실제 호출 200회는 유지한다.
- Gateway 예열의 기본 전체 상한은 core 대기를 포함해 120초다. core 직접 예열은 기본 30초를 유지한다. 공통 `STARTUP_WARMUP_MAX_DURATION`을 지정하면 두 앱 모두 해당 값을 사용하며 최대 2분 제한을 유지한다. readiness 대기 기본 상한을 180초로 늘린다.
- 예열 발생기 전용 HTTP 연결 풀은 설정한 동시성까지 허용하고 작업 종료 시 폐기한다. 운영 Gateway→core 연결 풀과 DB 풀 설정은 변경하지 않는다. 따라서 설정한 동시성은 발생기의 미완료 요청 상한이며 실제 서버 동시 실행 수를 보장하지 않는다.
- core 전용 경로는 서버 역할 보호를 유지하며 생성 후 3분·최대 30,000회·동시 1,000개로 제한한다. 음수 회차·전용 Redis 키·TTL 30초·finally 삭제·DB 롤백은 그대로다. 완료된 요청의 DB·Redis 호출 수와 회차·승자 수를 집계한다.
- 201/409가 의도한 승자·패자와 일치하는지 검사한다. 빈 응답·오류·전체 timeout 시 새 요청을 멈추고 취소한다. 이미 core에 도달한 작업은 롤백·TTL로 격리한다. 회차별 완료 로그와 최종 집계를 남긴다.

## 결과와 검증

호출량·동시 요청량을 외부 예열의 예매 생성 부분에 맞춘다. 외부 시나리오의 대기열·SSE·결제·실제 공유 좌석 경합 전체를 재현하는 것은 아니므로 동등한 JIT 상태나 성능을 보장하지 않는다. 새 기동 JFR로 Interpreted 표본을 확인하고 JFR OFF 성능을 별도 기록한다. 순수 콜드 회귀 생략 요청은 유지한다.

API 근거: [Reactor Flux concatMap·flatMap](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html), [WebClient Reactor Netty 설정과 자원 수명](https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html).

[확대 검증 보고](../performance/m5/16-startup-warmup-burst-volume.md): 전체 테스트·품질 검사·예열 활성 E2E 통과, 모든 측정에서 15,000건 예열 완료. JFR core Interpreted 23.1%, JFR OFF 3회 p95 741·843·853ms로 외부 예열 수준·300ms 목표는 미달이다. 정합성·오류·SSE는 통과했다.
