# M5-2 개선 및 측정 인계 (10) Step 7 보상 경로 부하 검증

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [Step 6 Gateway 지연 분석](09-step6-gateway-delay.md) · 다음: [Step 8 최종 측정과 M5 결과](11-step8-final-report.md)

## Step 7: 보상 경로 부하 검증 (2026-11-14, 정합성 검증이며 성능 개선율은 계산하지 않음)

### 결론

DB `(회차, 좌석)` 유니크 제약 방어선과 환불 완료·멱등성은 부하에서 검증됐다. 보상 3회(각 100쌍·회원 200명) 모두 중복 판매 0건·중복 환불 0건·환불 미완료 0건이고, 기대한 충돌 100건·환불 100건이 매 회차 실제 발생했다. **검증 중 Redis 불일치 결함을 발견해 고쳤다.** 선점 키가 유실돼 두 예매가 같은 좌석을 선점한 뒤 앞 예매가 확정에 이기면, 진 예매의 선점 해제(`release.lua`)가 이긴 예매의 좌석 스냅샷 SOLD를 AVAILABLE로 덮어썼다(첫 진단 실행: DB sold_seat 200 / Redis SOLD 150, 불일치 50건 = A가 이긴 쌍 수). DB는 맞았고 중복 판매는 없었지만 Redis 빠른 경로가 판매된 좌석을 다시 선점할 수 있었다(확정 시점에 DB 제약과 환불로 걸러진다). 도메인 보상 계약은 바꾸지 않고 해제가 SOLD를 되돌리지 않게 했다(아래 변경). 로드맵 체크박스는 갱신하지 않았다.

### 측정 조건

- 모든 실행은 **Scouter 없이**(`--no-scouter`, `env.txt`의 `scouter: 비활성`), 관측 스택 활성(`--observability`, 추적 0.1, scrape 5초), 앱 매번 새로 기동, 워밍업 없음, 코어 k6 `0-3`·앱 `4-15`, 힙 core 2g·realtime 1g·gateway 1g, 입장 배치 1,000명(보상 시나리오 설정), 5,000석 회차. HEAD `c61497d`에 미커밋 변경이 있는 상태(`env.txt`의 `dirty: yes`, `source.patch` 보존). 성능 기준(p95 등) 합격 판정이 아니라 정합성 검증이라 개선율·성능 중앙값은 계산하지 않는다.
- 이 시나리오는 Redis 선점 키 유실을 인위적으로 주입한 정합성 검증이며 정상 A·B 성능 비교와 별도로 해석한다.
- 포트 18090은 compose `ticketing-fake-pg-1` 컨테이너가 점유해 측정 전 중지하고 끝난 뒤 `docker start`로 복구했다(E2E가 다시 띄우면 재중지).

### 작업 1. 기존 `hot-seats --failure-inject` 재확인 (`build/loadtest/runs/20261114-204725-hot-seats-fi/1`, 1회, 방향성만)

설정은 실패 10%·웹훅 중복 30%다. 실제 건수는 `fake-pg.log` 기준이다.

| 항목 | 건수 |
|---|---|
| 결제 요청 접수(PG 거래) | 64 (`scenario=SUCCESS` 48, `FAIL` 5, `DUPLICATE_WEBHOOK` 11) |
| 실제 결제 실패(`PAYMENT_FAILED` 웹훅 전달) | 5 (실패율 7.8%) |
| 승인 웹훅 전달 / 고유 eventId | 81 / 59 → **중복 전달 22건**(복사본 2개씩 11거래) |
| 예매 상태 | CONFIRMED 59, CANCELLED 5(결제 실패 취소) |
| 정합성 | sold_seat 중복 0, 확정 좌석 중복 0, 판매 93 = 확정 93, Redis 불일치 0 |

설정률보다 실제 비율이 낮은 것은 표본이 작기 때문이다(64건, 시나리오 선택이 실패 → 중복 순으로 이어져 중복은 실패가 아닌 거래에서만 뽑힘). 결제 요청 64건 중 취소 5건은 `booking_not_confirmed` 5건과 일치한다. B 준비는 1,000/1,000 정시, 예매 p95 1,540ms로 k6 threshold(1,500ms)에 미달해 종료 코드 99(기록용이며 이 step의 판정 대상 아님).

### 작업 2~5. `seat-conflict-refund` 구현 (신규 시나리오)

- 쌍 100개, 회원 200명. 첫 단계 100 VU가 대기열·SSE 입장 후 2석을 PENDING 예매하고, runner가 그 예매의 겹치는 좌석 선점 키 하나만(키 값 = 해당 예매의 `hold_token`일 때만) 삭제한다(`conflict-injection.json`: 100/100 삭제, 소유자 불일치 0). 둘째 단계 200 VU가 B 회원의 대기열·입장·겹치는 좌석 예매를 마친 뒤 시작 시각(45초 뒤)에 A·B가 동시에 결제를 요청하고 같은 멱등 키로 한 번 더 요청한다. 승패는 고정하지 않는다.
- 검증은 `scripts/loadtest/verify-compensation.sh`가 한다. booking·payment를 각 스키마에서 따로 조회해 예매 ID(주문 ID)로 jq에서 대조하고 PG는 `GET /v1/transactions`로 확인한다. 스키마 간 조인·FK는 없다. 쌍마다 확정 1·`SEAT_ALREADY_SOLD` 취소 1·패자 `sold_seat` 0·승자 sold_seat = 좌석 수·승자 결제 SUCCEEDED·패자 결제 `REFUNDED`(환불 요청·완료 시각 존재)·PG 승자 APPROVED·패자 REFUNDED(금액 일치)·결제 웹훅 이벤트(승자 1·패자 2, 중복 제거)·Redis 승자 SOLD·패자 전용 좌석 AVAILABLE·선점 키 잔존 없음을 확인한다. 환불은 완료 조건(PENDING 0, 결제 REQUESTED·REFUND_REQUESTED 0, REFUNDED ≥ 취소 수)과 상한 120초를 두고 기다리며 미완료는 실패다. 원장·Redis 상태는 항상 `ledger-*.json`으로 보존한다.
- 정상 A·B의 `unexpected_errors` 정의는 바꾸지 않았다. 이 시나리오의 k6는 같은 `request` 래퍼를 쓰므로 모든 응답이 2xx여야 정상이다(재결제 요청도 202).
- runner·시드·`metrics-delta.py`(통제 입력 비교를 `eq`로)·k6 시나리오(`loadtest/scenarios/seat-conflict-refund.ts`, `loadtest/lib/conflict.ts`)를 추가·수정했다. 서버 지표·`k6-summary.json`은 둘째 단계 구간만이고 첫 단계는 `k6-first.log`·`k6-first-summary.json`이다.

### 보상 결과

| 실행 | 승자 A/B | 확정 | `SEAT_ALREADY_SOLD` 취소 | 패자 결제 REFUNDED | 결제 FAILED | 환불 미완료 | 중복 환불 거래 | 승자 환불 | 승인 웹훅 전달 / 고유 / **중복** | 환불 웹훅 | 환불 대기 초 | 재결제 요청 202 | 정합성 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---|
| 진단 1(수정 전) `20261114-205059-seat-conflict-refund/1` | — | 100 | 100 | — | — | — | — | — | — | — | — | — | **Redis 불일치 50(위반)** |
| 진단 2(수정 후) `20261114-205417-seat-conflict-refund/1` | 51 / 49 | 100 | 100 | 100 | 0 | 0 | 0 | 0 | 318 / 200 / **118** | 100 | 2 | 200/200 | 통과 |
| 보상 1 `20261114-205559-seat-conflict-refund/1` | 53 / 47 | 100 | 100 | 100 | 0 | 0 | 0 | 0 | 308 / 200 / **108** | 100 | 2 | 200/200 | 통과 |
| 보상 2 `…/2` | 57 / 43 | 100 | 100 | 100 | 0 | 0 | 0 | 0 | 340 / 200 / **140** | 100 | 2 | 200/200 | 통과 |
| 보상 3 `…/3` | 53 / 47 | 100 | 100 | 100 | 0 | 0 | 0 | 0 | 324 / 200 / **124** | 100 | 2 | 200/200 | 통과 |

- 진단 1은 보상 검증 스크립트의 실행 권한 누락(`허가 거부`)으로 보상 검증이 실행되지 않았다. 같은 실행의 `verify-integrity.sh`가 Redis 불일치 50건(DB sold_seat 200, Redis SOLD 150)을 찾아 수정으로 이어졌다. 진단 2는 수정 직후 1회 확인이며 보상 1~3이 공식 3회다. 네 회차 모두 sold_seat 중복 0·확정 좌석 중복 0·판매 수 일치·Redis 불일치 0(진단 1 제외)이다.
- 실제 전달 건수는 `fake-pg.log` 기준이다. 보상 3회의 PG 시나리오 선택은 `DUPLICATE_WEBHOOK` 54·70·62건, `SUCCESS` 146·130·138건, `FAIL` 0건(설정 실패율 0). 결제 실패 취소는 0건이므로 좌석 충돌 환불 100건과 구분된다. 웹훅 이벤트는 `payment.webhook_event`에서 승자 거래 1건·패자 거래 2건(승인 + 환불 완료)으로 중복 전달이 걸러졌고 추가 확정·추가 환불은 없었다. PG 환불 접수는 패자 거래당 정확히 1건이다.
- k6: 모든 실행에서 k6 종료 코드 0·threshold 통과·`http_req_failed` 0·예상 밖 오류 0. B 회원 정시 준비 100/100·지각 0·예매 시도 100. 보상 3회 둘째 단계의 예매 생성 p95는 327.6 · 232.8 · 335.8ms(100건 표본, 참고용이며 정상 B와 비교하지 않음). 완료 기준 중 예상 충돌 100건·환불 100건 도달은 3회 모두 충족했다.

### 변경

- `core/src/main/resources/redis/booking/release.lua`: 소유자 선점 키는 지우되 스냅샷이 이미 SOLD면 AVAILABLE로 덮어쓰지 않고 해제 목록·Stream 이벤트에서도 뺀다. 이 결함은 좌석 확정(최종 방어선)이 아니라 Redis 보조 상태에서 생겼으며 DB 유니크 제약은 그대로다. 신규 단위 테스트(`RedisSeatHoldAdapterTests`의 「선점 키가 유실돼 두 예매가 …」)로 고정했다. 수정 전 상태의 이 테스트 단독 실패 확인은 하지 못했고(작업 트리 임시 변경 금지) 대신 수정 전 진단 1의 부하 실행이 재현 증거다. `docs/architecture/flows.md`에 해제 규칙을 한 줄 추가했다.
- 신규: `scripts/loadtest/verify-compensation.sh`, `loadtest/scenarios/seat-conflict-refund.ts`, `loadtest/lib/conflict.ts`. 수정: `scripts/loadtest/run.sh`(시나리오·두 단계 실행·선점 키 유실 주입·manifest·보상 검증 연결), `scripts/loadtest/seed.sh`(`conflictPairs`), `scripts/loadtest/metrics-delta.py`, `docs/performance/loadtest-runbook.md`.
- 보존한 설계 규칙: DB 유니크 제약·판매 행은 조작하지 않았고(키 삭제는 부하 전용 회차의 해당 예매 hold_token 키뿐) 운영 API를 만들지 않았다. 결제 멱등 키·웹훅 멱등 검사는 그대로다. 모듈·스키마 경계는 바뀌지 않았다.

### 검증

- 전체 `run-tests.sh` 통과, `ktlintCheck detekt` 통과, E2E(`./scripts/e2e/run.sh`) 통과. 셸 `bash -n`(run.sh·seed.sh·verify-compensation.sh)과 `metrics-delta.py` 컴파일 통과, smoke `20261114-210052-smoke/1` 통과. 문서 검사는 `./scripts/agent/docs/check-docs.sh`로 실행했다.
- Modulith 구조 검증은 모듈 의존을 바꾸지 않아 생략.

### 한계와 다음 가설

- 보상 3회는 같은 설정이고 표본이 100쌍/회다. 승자 비율은 49~57%로 고정하지 않았으며 이긴 쪽의 편향은 보지 않았다.
- 주입은 겹치는 좌석 1개의 선점 키만이다. 선점 키가 더 많이 유실되거나 확정 직전에 TTL이 끝나는 경우, 환불 PG 실패·웹훅 지연과의 조합은 검증하지 않았다.
- PG 실제 전달 건수는 fake-pg 로그 파싱이다(재시작하면 초기화되는 메모리 저장소이므로 실행당 앱 재기동과 맞는다). 환불 웹훅이 core에 도달해 REFUNDED가 된 것은 payment 원장으로 확인했다.
- 가설: 선점 키 유실이 TTL 만료나 Redis 장애처럼 현실적인 경로에서도 같은 해제 결함을 만들 수 있으므로, 이후 Redis 보정(M2 Batch의 스냅샷 보정)이 SOLD를 되돌리지 않는지 별도 점검할 가치가 있다.
