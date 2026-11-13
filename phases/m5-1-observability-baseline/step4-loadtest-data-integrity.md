# Step 4: loadtest-data-integrity

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/resources/db/migration/V3__create_member_tables.sql` (`member.member`)
- `core/src/main/resources/db/migration/V5__create_booking_tables.sql` (`booking.booking`, `booking_seat`, `sold_seat`)
- `core/src/main/kotlin/io/github/ticketing/catalog/web/CatalogRegistrationController.kt` (공연장·공연·회차 등록 API와 요청 형식. 공연장 좌석 최대 5,000석)
- `core/src/main/kotlin/io/github/ticketing/catalog/web/CatalogQueryController.kt` (`GET /api/v1/schedules/{id}/seats`)
- `core/src/main/kotlin/io/github/ticketing/member/security/JwtTokenProvider.kt`, `JwtProperties.kt` (클레임 `sub`·`role`, HS256, secret 문자열의 바이트를 키로 사용)
- `docs/architecture/redis.md § 좌석 선점 키 형식` (스냅샷 키 `booking:seats:{<scheduleId>}`, 값 `AVAILABLE`/`HELD:<ms>`/`SOLD`)
- `scripts/e2e/run.sh` (스크립트 관례: `set -euo pipefail`, 저장소 루트 기준, `.env` 사용, 오류 메시지 한국어)

## 배경

k6가 쓸 회원 1만 명과 회차를 준비하고, 측정이 끝난 뒤 중복 판매가 없는지 검증한다(M5 완료 기준). 회원은 로그인하지 않으므로 SQL로 넣는다. 회차는 등록 서비스를 거쳐야 `ScheduleRegistered` 이벤트가 booking 좌석 사본과 Redis 스냅샷을 만들므로 **core API로 등록**한다. 좌석이 판매되므로 측정할 때마다 새 회차를 만든다.

## 작업

모든 스크립트는 저장소 루트에서 실행되고, 호스트에 `psql`이 없으므로 `docker compose exec -T postgres psql`, `docker compose exec -T redis redis-cli`를 쓴다. `jq`·`openssl`·`curl`은 있다고 가정한다.

### 1. `scripts/loadtest/lib/jwt.sh`

`sign_jwt <memberId> <role>`: `.env`의 `TICKETING_JWT_SECRET`으로 HS256 access 토큰을 만든다(`sub`, `role`, `iat`, `exp`=1시간 뒤, base64url). k6 쪽 서명은 step 6에서 TypeScript로 따로 만든다.

### 2. `scripts/loadtest/seed.sh`

- `seed.sh members [--count 10000]`: `member.member`에 USER 회원을 넣는다. 이메일 `load-<5자리>@loadtest.local`, 이름 `load-<n>`, 비밀번호 해시는 고정 bcrypt 문자열(로그인하지 않으므로 값 자체는 무관하나 형식은 `$2a$10$...` 유지). 주최자 1명(`load-organizer@loadtest.local`, ORGANIZER)도 넣는다. 이메일 유니크 제약을 이용해 다시 실행해도 늘지 않게(`ON CONFLICT DO NOTHING`) 한다. 결과로 USER 회원 ID 범위와 주최자 ID를 `build/loadtest/members.json`에 쓴다.
- `seed.sh schedule --scenario queue-surge|hot-seats`: 주최자 토큰으로 core(`http://localhost:8080`)를 직접 호출한다.
  - 공연장 `loadtest-venue`(5구역 × 25열 × 40석 = 5,000석, 1~2열 VIP, 3~8열 R, 9~16열 S, 나머지 A)는 없을 때만 만든다. 있으면 조회해 재사용한다(조회 API가 없으면 공연장 ID를 `build/loadtest/venue.json`에 저장해 재사용).
  - 실행마다 공연(`loadtest-<scenario>-<timestamp>`)과 회차(시작 시각 7일 뒤)를 새로 만든다.
  - `GET /api/v1/schedules/{id}/seats`로 좌석 ID를 받고, Redis 스냅샷 키가 생길 때까지(최대 10초) 기다린다.
  - `build/loadtest/schedule-<scenario>.json`에 `{scheduleId, performanceId, seatIds, hotSeatIds}`를 쓴다. `hotSeatIds`는 `A`구역 1열 40석 + 2열 40석 + `B`구역 1열 20석, 총 100석이다.

### 3. `scripts/loadtest/verify-integrity.sh <scheduleId>`

측정 뒤 실행한다. 각 검사 결과를 한 줄씩 출력하고 마지막에 JSON 요약(`build/loadtest/integrity-<scheduleId>.json`)을 쓴다. 위반이 하나라도 있으면 exit 1이다.

| 검사 | 기준 |
|---|---|
| `sold_seat` 중복 | `(schedule_id, seat_id)`별 2건 이상인 행 0건 |
| 확정 좌석 중복 | 같은 좌석이 `CONFIRMED` 예매 둘 이상에 속한 경우 0건 |
| 판매 수 일치 | `CONFIRMED` 예매의 좌석 수 = 해당 회차 `sold_seat` 수 |
| Redis 일치 | 스냅샷의 `SOLD` 좌석 집합 = DB `sold_seat` 좌석 집합 |
| 참고(위반 아님) | 상태별 예매 수(`PENDING`·`CONFIRMED`·`CANCELLED` 등 실제 enum 값), 판매 좌석 수 |

DB 검사는 booking 스키마 안에서만 조회한다. 다른 스키마(payment)와 조인하지 않는다.

## Acceptance Criteria

```bash
bash -n scripts/loadtest/seed.sh scripts/loadtest/verify-integrity.sh scripts/loadtest/lib/jwt.sh
test -x scripts/loadtest/seed.sh && test -x scripts/loadtest/verify-integrity.sh
rg -q 'ON CONFLICT' scripts/loadtest/seed.sh
rg -q 'sold_seat' scripts/loadtest/verify-integrity.sh
```

## 검증 절차

1. 위 AC를 실행한다.
2. 실제로 동작을 확인한다: `docker compose up -d --wait postgres redis`, `./gradlew -q :core:bootJar`, core jar를 백그라운드로 띄운다(로그는 `build/loadtest/logs/core-seed-check.log`). 이어서 `seed.sh members --count 100`을 두 번 실행해 행 수가 늘지 않는지 확인하고, `seed.sh schedule --scenario hot-seats` 실행 후 `verify-integrity.sh <scheduleId>`가 exit 0인지 확인한다.
3. 띄운 core 프로세스를 종료한다.

## 금지사항

- 회차·공연장을 SQL로 직접 넣지 마라. 이유: `ScheduleRegistered` 이벤트가 발행되지 않아 booking 좌석 사본과 Redis 스냅샷이 만들어지지 않는다.
- core에 부하 테스트 전용 API나 프로필을 추가하지 마라. 이유: 이 step은 스크립트만 다룬다. 기존 API로 부족하면 보고한다.
- 검증 SQL에서 스키마를 넘는 조인을 하지 마라. 이유: 설계 규칙 2(모듈별 스키마 경계). 필요하면 결과를 따로 조회해 스크립트에서 비교한다.
- `.env`의 비밀 값을 출력하거나 `build/` 밖 파일에 쓰지 마라. 이유: 비밀 값은 저장소와 로그에 남기지 않는다.
