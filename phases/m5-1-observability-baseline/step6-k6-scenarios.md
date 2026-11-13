# Step 6: k6-scenarios

- model: 기본 / reasoning_effort: high

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `loadtest/scenarios/smoke.ts`, `scripts/loadtest/run.sh` (step 5 결과. 환경 변수 `BASE_URL`, `TICKETING_JWT_SECRET`, `MEMBERS_FILE`, `SCHEDULE_FILE`과 실행 방식)
- `build/loadtest/members.json`, `build/loadtest/schedule-<scenario>.json` 형식은 `scripts/loadtest/seed.sh`(step 4)에서 확인한다
- `docs/architecture/flows.md § 6.1 대기열과 입장` (대기열 API, SSE 이벤트 이름·본문, rate limit, 입장 토큰 헤더 `X-Admission-Token`)
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/provided/web/BookingController.kt` (예매 생성: `Idempotency-Key` 헤더, 본문 `{scheduleId, seatIds(1~4)}`, 201/200. 결제: `POST /{id}/payments` 202. 상세: `GET /{id}`)
- `core/src/main/kotlin/io/github/ticketing/member/web/SseTicketController.kt` (`POST /api/v1/sse-tickets`)
- `core/src/main/kotlin/io/github/ticketing/booking/application/service/BookingExceptions.kt` (409 `SEAT_ALREADY_HELD` 등 오류 코드)
- xk6-sse README(빌드한 태그 기준), k6 공식 문서(TypeScript 실행, scenarios/executors, thresholds, custom metrics)

## 배경

합의된 두 시나리오를 구현한다(ADR 0024). k6는 Gateway(8000)를 직접 호출하고, access 토큰은 k6가 `TICKETING_JWT_SECRET`으로 직접 서명한다(HS256, 클레임 `sub`=회원 ID 문자열, `role`=`USER`, `iat`, `exp`). SSE 티켓은 1회용이므로 스트림을 열 때마다 새로 발급한다.

## 작업

### 1. 공용 모듈 `loadtest/lib/`

| 파일 | 내용 |
|---|---|
| `jwt.ts` | `signAccessToken(memberId: number, role: string): string`. `k6/crypto` HMAC-SHA256, base64url |
| `api.ts` | Gateway 호출 래퍼. 요청마다 `tags.name`을 고정 이름으로 붙인다(`queue_enter`, `sse_ticket`, `booking_create`, `booking_pay`, `booking_detail`). URL에 ID가 들어가도 지표 cardinality가 늘지 않게 하기 위해서다 |
| `sse.ts` | xk6-sse로 `waitForAdmission(scheduleId, token): {admissionToken, waitedMs}`(순번 스트림. `admitted`에서 닫고, `removed`·오류면 실패), `readSeatSnapshot(scheduleId, token): seats`(좌석 스트림. `snapshot` 받으면 닫음) |
| `metrics.ts` | 사용자 지표(아래) |
| `data.ts` | `MEMBERS_FILE`, `SCHEDULE_FILE`을 `open()`으로 읽어 `SharedArray`로 공유. VU 번호 → 회원 ID 매핑(VU마다 다른 회원) |

사용자 지표:

| 이름 | 종류 | 의미 |
|---|---|---|
| `queue_wait` | Trend(ms) | 등록부터 `admitted`까지 |
| `sse_connect_success` | Rate | 스트림 연결 성공(첫 이벤트 수신) |
| `booking_conflict` | Counter | 409 `SEAT_ALREADY_HELD` |
| `booking_confirmed` | Counter | 확정된 예매 수 |
| `seats_confirmed` | Counter | 확정된 좌석 수 |
| `confirm_latency` | Trend(ms) | 결제 요청부터 `CONFIRMED` 확인까지 |
| `unexpected_errors` | Rate | 예상하지 못한 응답 비율. 예상 응답은 2xx, 409 `SEAT_ALREADY_HELD`, 429 `RATE_LIMITED`. 그 밖의 4xx·5xx·네트워크 오류는 오류로 센다 |

### 2. VU 흐름 (두 시나리오 공통)

1. 대기열 등록 → SSE 티켓 → 순번 스트림에서 `admitted`까지 대기.
2. SSE 티켓 → 좌석 스트림 스냅샷 → 후보 좌석에서 `AVAILABLE`인 좌석을 고른다.
3. `POST /api/v1/bookings`(`X-Admission-Token`, 새 `Idempotency-Key`). 409면 스냅샷을 다시 읽어 최대 2회 재시도한다(시나리오 B는 재시도하지 않는다). 429면 `Retry-After`나 1초 대기 후 재시도한다.
4. 성공하면 결제 요청 → `GET /bookings/{id}`를 1초 간격으로 최대 30초 폴링한다. `CONFIRMED`면 확정 지표를 올리고, 실패·취소 상태면 그 상태를 tag로 남긴다.
5. 대기열 이탈은 하지 않는다(입장 후 토큰 TTL로 정리).

### 3. `loadtest/scenarios/queue-surge.ts` (시나리오 A)

- 실행기 `per-vu-iterations`, VU 수 `__ENV.VUS`(기본 10000), 반복 1, `maxDuration` 15분. 모든 VU가 시작 직후 진입한다.
- 후보 좌석은 회차 전체 좌석이고, 장수는 1~4매에서 무작위로 고른다.
- `discardResponseBodies`는 켜고, 본문이 필요한 요청에만 `responseType: 'text'`를 준다(k6 메모리 절약).

### 4. `loadtest/scenarios/hot-seats.ts` (시나리오 B)

- VU 수 `__ENV.VUS`(기본 1000). `setup()`이 `startAt = now + 60초`를 반환한다. 각 VU는 입장 후 `startAt`까지 기다렸다가 동시에 예매 생성을 보낸다.
- 후보 좌석은 `hotSeatIds`(100석)이고, 그중 1~4매를 무작위로 고른다. 좌석 스트림은 읽지 않는다(동시 경합이 목적이다).
- `handleSummary`에서 `seats_confirmed`가 100을 넘으면 경고 줄을 출력한다. 판정은 `verify-integrity.sh`가 한다.

### 5. thresholds (두 시나리오 공통, 잠정)

- `http_req_duration{name:booking_create}`: `p(95)<300`
- `unexpected_errors`: `rate<0.01`
- `sse_connect_success`: `rate>0.999`
- threshold 실패로 테스트를 중단하지 않는다(`abortOnFail` 금지). 기준 측정의 미달 항목은 그대로 기록한다.

### 6. `scripts/loadtest/run.sh`

`--vus N` 옵션을 추가해 k6에 `VUS`로 넘긴다. 검증과 축소 실행용이다.

## Acceptance Criteria

```bash
test -f loadtest/scenarios/queue-surge.ts && test -f loadtest/scenarios/hot-seats.ts
rg -q "k6/x/sse" loadtest/lib/sse.ts
rg -q 'unexpected_errors' loadtest/lib/metrics.ts
./scripts/loadtest/run.sh --scenario hot-seats --vus 200
./scripts/loadtest/run.sh --scenario queue-surge --vus 500
```

`run.sh`가 k6 threshold 미달만으로 실패하면(k6 exit 99) AC 실패로 보지 않는다. 정합성 검증 실패와 스크립트 오류만 실패다. 이 구분은 `run.sh` 요약 출력에 드러나야 한다.

## 검증 절차

1. 위 AC를 실행한다. 축소 실행에서 `unexpected_errors`가 0보다 크면 원인(스크립트 버그인지 서버 응답인지)을 실행 디렉터리의 k6 출력과 앱 로그에서 확인한다. 스크립트 버그면 고친다. 서버 문제로 보이면 고치지 말고 보고한다.
2. `queue-surge --vus 1000`을 한 번 실행하면서 k6 프로세스의 최대 RSS(`/usr/bin/time -v` 또는 `ps`)를 측정한다. 1만 VU로 비례 추정한 값이 12GB를 넘으면 결과 보고에 적는다. 시나리오 규모를 임의로 줄이지 않는다.

## 금지사항

- 테스트 전용 우회 경로(대기열 생략, 입장 토큰 위조, core 직접 호출)를 쓰지 마라. 이유: Gateway·realtime·core 전 구간이 측정 대상이다. 시나리오 B의 동시성은 입장 배치 크기 설정으로만 만든다.
- 회원 한 명을 여러 VU가 공유하지 마라. 이유: 회원별 rate limit과 대기열 항목이 겹쳐 결과가 왜곡된다.
- URL 원문을 지표 태그로 남기지 마라. 이유: 회차·예매 ID마다 시계열이 생겨 Prometheus remote-write가 폭증한다.
- 서버 코드나 설정(rate limit, 입장 속도 기본값)을 고치지 마라. 이유: 기준 측정 전이다. 개선은 M5-2에서 한다.
- `TICKETING_JWT_SECRET`을 콘솔·요약 JSON에 출력하지 마라.
