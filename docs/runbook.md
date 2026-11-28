# 런북

로컬·개발 환경에서 스택을 운영하고 문제를 진단하는 절차다. 처음 환경을 구성하는 방법은 [onboarding.md](./onboarding.md)를 본다. 아직 배포 환경(k8s)이 없으므로 M6 이후 이 문서를 확장한다.

## 1. 구성 요소와 의존

| 구성 | 포트 | 의존 | 상태 확인 |
|---|---|---|---|
| gateway | 18000 | Redis, core, payment, settlement, realtime | `/actuator/health` |
| core | 18080 | PostgreSQL, Redis, Kafka | `/actuator/health` |
| payment | 18085 | PostgreSQL(`payment_db`), Kafka, fake-pg | `/actuator/health` |
| settlement | 18086 | PostgreSQL(`settlement_db`), Kafka | `/actuator/health` |
| realtime | 18081 | Redis | `/actuator/health` |
| web | 13000 | Gateway, Redis(BFF 세션) | 브라우저 |
| fake-pg | 18090 | payment(웹훅) | `/actuator/health` (호스트 jar) |
| PostgreSQL | 15432 | - | `pg_isready` |
| Redis | 16379 | - | `redis-cli ping` |
| Kafka | 19092 | - | `docker compose ps` (healthy) |

기동 순서는 PostgreSQL·Redis·Kafka·fake-pg → core·payment·settlement·realtime → gateway → web이다. 종료는 역순이다.

## 2. 기동·종료·초기화

```bash
docker compose up -d --wait         # 인프라(PostgreSQL, Redis, Kafka) 기동
./scripts/dev/run.sh --fake-pg      # fake-pg는 호스트 jar로 기동(로그: build/dev/logs/fake-pg.log)
./scripts/dev/run.sh --payment      # 결제 앱(18085). 결제 흐름에는 core와 함께 띄운다
./scripts/dev/run.sh --settlement   # 정산 앱(18086)
./scripts/db/ensure-payment-db.sh   # 기존 볼륨에 payment_db·전용 계정이 없을 때(멱등)
docker compose ps                   # 상태
docker compose down                 # 종료 (데이터 유지)
docker compose down -v              # 종료 + PostgreSQL 볼륨 삭제 (데이터 전부 사라짐)
```

- `down -v`는 되돌릴 수 없다. 로컬 데이터를 버려도 될 때만 쓴다. core·payment·settlement 앱은 기동 때 각자의 DB에 Flyway 마이그레이션을 적용한다.
- core에 옛 정산 데이터가 있으면 정산 앱을 시작하기 전에 [정산 DB 이전](#10-정산-db-이전-core-db-settlement-스키마--settlement_db)을 진행한다.
- Redis는 볼륨이 없어서 컨테이너를 다시 만들면 대기열·선점·세션이 사라진다.
- Compose 기본 네트워크의 브릿지 이름은 `br-ticketing`으로 고정한다(서브넷은 Docker가 배정). 방화벽 규칙을 서브넷이 아닌 이 인터페이스에 걸어서 네트워크를 재생성해도, 다른 프로젝트와 서브넷이 겹쳐도 유지된다. 다른 프로젝트도 고유한 브릿지 이름을 쓰고 인터페이스 기준으로 허용하면 같은 문제를 피한다.
- Linux에서 UFW를 쓰면 최초 1회 `sudo ./scripts/setup-firewall.sh`를 실행한다(멱등). 기존 네트워크에는 이름이 적용되지 않으므로 `docker compose down` 후 다시 기동한다(Redis 데이터는 사라진다).
- 포트가 이미 사용 중이면 `ss -ltnp 'sport = :18080'`으로 점유 프로세스를 찾는다.

### 2.1 로컬 포스터 저장소 RustFS 전환

1. `.env`의 기존 설정값을 유지하면서 변수 이름을 바꾼다. 기존 이름은 `TICKETING_` 뒤에 아래 표의 접미사를 붙인 이름이다.

   | 기존 접미사 | 새 변수 |
   |---|---|
   | `MINIO_USER` | `TICKETING_OBJECT_STORAGE_ACCESS_KEY` |
   | `MINIO_PASSWORD` | `TICKETING_OBJECT_STORAGE_SECRET_KEY` |
   | `MINIO_PORT` | `TICKETING_OBJECT_STORAGE_PORT` |
   | `MINIO_CORS_ORIGINS` | `TICKETING_OBJECT_STORAGE_CORS_ORIGINS` |

2. 기존과 같은 Compose 프로젝트에서 아래 명령을 실행한다. `--remove-orphans`가 이전 MinIO 컨테이너를 정리해 포트를 비운다. 기존 `minio-data` 볼륨은 롤백을 위해 보관한다.

   ```bash
   docker compose up -d --wait --remove-orphans object-storage
   docker compose run --rm --no-deps object-storage-init
   ```

3. 빈 로컬 DB에서는 core와 Gateway를 기동한 뒤 `./scripts/dev/seed-demo.sh`로 시연 데이터와 포스터를 생성한다. 이미 시드한 로컬 데이터를 모두 버려도 되면 `./scripts/dev/seed-demo.sh --reset`으로 다시 생성한다. `--reset`은 메인·결제 DB의 데이터와 Redis 데이터를 지운다.
4. 기존 포스터를 보존해야 하면 DB를 초기화하지 않는다. 같은 Compose 프로젝트에서 이전 Compose 설정으로 `minio` 서비스만 별도 포트에 띄워 `minio-data` 볼륨을 읽는다. S3 클라이언트로 양쪽 엔드포인트에 접속해 `posters/` 객체를 RustFS의 포스터 버킷에 복사한다. DB가 참조하는 객체 키와 원래 `Content-Type`을 유지한다. 복사가 끝나면 이전 서비스를 종료한다.
5. 화면에서 포스터가 정상 표시되고 롤백이 필요 없어진 뒤 `docker volume ls --filter label=com.docker.compose.volume=minio-data`로 기존 프로젝트의 볼륨 이름을 확인하고 `docker volume rm <볼륨 이름>`으로 해당 볼륨만 직접 삭제한다. [ADR 0053](./adr/0053-poster-storage-rustfs.md)

## 3. 장애 진단

### 3.1 앱이 뜨지 않는다

1. `docker compose ps`에서 postgres, redis, kafka가 `healthy`인지 본다.
2. `.env`가 있고 `TICKETING_POSTGRES_*`, `TICKETING_JWT_SECRET`, `FAKE_PG_WEBHOOK_SECRET`이 채워져 있는지 본다. `.env.example`과 비교한다.
3. 빌드가 `:core:generateJooq`에서 실패하면 Docker가 떠 있는지 확인한다. 코드 생성이 Docker를 쓴다.
4. E2E로 띄웠다면 `build/e2e/logs/`의 앱별 로그를 본다.

### 3.2 결제가 PENDING에서 넘어가지 않는다

결제는 이벤트 기반이다. core가 `PaymentRequested`를 Kafka로 내보내면 payment 앱이 받아 fake-pg에 요청하고, fake-pg가 payment 앱에 웹훅으로 결과를 돌려준다. 결과는 payment 앱이 Kafka로 내보내 core가 받는다.

1. `build/dev/logs/fake-pg.log`(E2E는 `build/e2e/logs/fake-pg.log`)에서 요청이 도착했고 웹훅을 보냈는지 본다. payment 앱이 떠 있는지(`--payment`, 로그는 `payment.log`)도 확인한다.
2. 웹훅이 닿지 않으면 payment 앱이 18085에서 호스트로 떠 있는지, `FAKE_PG_WEBHOOK_URL`(기본 `http://localhost:18085/api/v1/payments/webhooks/fake-pg`)이 맞는지 본다. 웹훅은 Gateway를 거치지 않는다.
3. 웹훅이 거절되면 payment 앱과 fake-pg의 `FAKE_PG_WEBHOOK_SECRET`이 같은지 본다. 서명 검증이 이 값을 쓴다.
4. 웹훅은 중복 도착할 수 있다. 멱등 키로 걸러지므로 중복 로그 자체는 정상이다.
5. 결제 기록과 PG 거래의 불일치는 `paymentReconciliationJob`(4절)으로 검출한다. 자동 보정은 하지 않는다.
6. 만료된 예매에 결제 성공이 늦게 도착하면 자동 환불 경로로 처리된다. 흐름은 [flows.md §6.4](./architecture/flows.md#64-결제-이벤트-기반)를 본다.

### 3.3 대기열·SSE가 동작하지 않는다

1. realtime이 `UP`인지, Redis에 닿는지 본다. realtime은 PostgreSQL을 쓰지 않는다.
2. 입장 토큰 없이 예매 API를 부르면 Gateway가 막는 것이 정상이다. 토큰은 realtime이 발급하고 Gateway가 검증한다.
3. SSE 연결이 거절되면 SSE 티켓을 확인한다. 티켓은 TTL 30초에 1회 소비되므로 만료·재사용이면 새로 발급한다.
4. 재연결 후 이벤트가 이어지지 않으면 `Last-Event-ID` 처리를 확인한다. 좌석 이벤트는 Redis Stream `booking:seat-events:{scheduleId}`에서 읽는다(`MAXLEN ~ 10000`).
5. Redis 키 확인 예시:

```bash
docker compose exec redis redis-cli ZCARD 'queue:waiting:{1}'      # 대기 인원
docker compose exec redis redis-cli SMEMBERS queue:active           # 대기자가 있는 회차
docker compose exec redis redis-cli HGETALL 'booking:seats:{1}'     # 좌석 스냅샷
```

키 형식 전체는 [redis.md](./architecture/redis.md)를 본다. 운영 키를 직접 수정·삭제하지 않는다. 로컬에서도 원인 확인 전에는 `FLUSHALL`로 덮지 않는다.

### 3.4 좌석 상태가 이상하다

- 좌석 확정의 최종 기준은 DB의 `(회차, 좌석)` 유니크 제약(`sold_seat`)이다. Redis 스냅샷이 DB와 다르면 DB가 맞다.
- 커밋 후 Redis 반영 실패로 어긋난 스냅샷은 `reconcileSeatSnapshotJob`(4절)으로 DB 기준에 맞춘다.
- 선점 키 TTL은 5분이다. TTL이 끝난 `HELD`는 읽는 쪽이 만료로 판정한다.

### 3.5 web 로그인·세션 문제

- 세션은 Redis `bff:session:<sessionId>`에 14일 TTL로 있다. Redis를 초기화하면 모든 사용자가 다시 로그인해야 한다.
- access 토큰 갱신은 `bff:refresh-lock:<sessionId>` 락으로 직렬화한다. 락이 남아 있어 갱신이 막히면 TTL 만료를 기다린다.
- 브라우저에 JWT가 보이면 설계 위반이다. 즉시 [ADR 0013](./adr/0013-jwt-behind-bff.md)을 기준으로 원인을 찾는다. `./scripts/e2e/run.sh --web`이 이 항목을 검사한다.
- web 디렉터리의 `.env.local`의 `GATEWAY_URL`, `GATEWAY_PUBLIC_URL`, `REDIS_URL`, `WEB_ORIGIN`이 실제 주소와 같은지 본다. HTTPS가 아닌 로컬에서는 `SESSION_COOKIE_SECURE=false`여야 쿠키가 저장된다.

### 3.6 rate limit에 걸린다

Gateway의 Redis rate limiter가 응답을 429로 내린다. 부하 테스트 중이면 정상이다. 일반 사용 중에도 걸리면 Gateway의 rate limit 설정을 확인한다.

### 3.7 미완료 이벤트 발행이 쌓인다

Modulith 이벤트 발행 레지스트리(`public.event_publication`)에서 `completion_date`가 비어 있는 행이 미완료 발행이다. 로컬 리스너 처리나 Kafka 외부화 전송이 실패했거나 처리 중 프로세스가 종료되면 이 테이블에 남는다. 이벤트를 발행한 앱의 DB에서 찾는다.

| 발행 앱 | 조회 DB | 이벤트 예 |
|---|---|---|
| core | core DB (`TICKETING_POSTGRES_DB`) | `PaymentRequested`, `RefundRequested`, `BookingConfirmed`, `BookingCancelled`, `PerformanceCancelled`, `BookingForceCancelled`, 쿠폰 예약·보상 이벤트 |
| payment | `payment_db` (`TICKETING_PAYMENT_POSTGRES_DB`) | `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded` |

**증상·지표**

상시 앱 core와 payment가 각자의 미완료 발행을 수집한다. 정산 서비스에는 `event_publication` 테이블이 없어 이 지표를 끈다. 배치 프로필에서도 중복 수집을 끈다.

| 메트릭 | 의미 |
|---|---|
| `ticketing_event_publication_incomplete` | 미완료 발행 건수 |
| `ticketing_event_publication_oldest_incomplete_age_seconds` | 가장 오래된 미완료 발행의 경과 시간(초). 없으면 0 |

운영 대시보드 `Ticketing Overview`에서 상태 요약의 "미완료 이벤트 발행 건수"와 비동기 흐름의 "최고령 미완료 이벤트 경과"를 본다. 값은 10초 캐시이고 조회 실패 시 직전 값을 쓰며, 처음부터 실패하면 `NaN`이다. 정상 상태는 건수가 0이거나 잠깐 올랐다 내려오는 것이다. 최고령이 계속 늘면 재처리가 필요한 건이다.

결제 확정 지연은 `ticketing_booking_payment_confirmation_lag_seconds`(Timer, 히스토그램)다. PG가 알린 결제 발생 시각(`PaymentSucceeded.paidAt`)부터 booking이 예매를 확정한 시각까지이며, 실제로 확정한 건만 기록한다(중복·만료 후 승인·환불 경로 제외). `Ticketing Overview`의 "결제 확정 지연 p95"에서 운영 추세를 보고, 부하 측정 비교에는 `Ticketing Load Test` 또는 `server-delta.json`의 p50·p95·p99를 사용한다. PG와 core의 시계가 다르면 오차가 섞인다(로컬은 같은 호스트).

**확인 SQL**

```bash
docker compose exec postgres psql -U "$TICKETING_POSTGRES_USER" -d "$TICKETING_POSTGRES_DB" -c \
  "select id, listener_id, event_type, status, completion_attempts, publication_date, last_resubmission_date
     from public.event_publication where completion_date is null order by publication_date limit 20;"
```

- payment DB를 확인할 때는 위 명령의 `-U`·`-d`에 `$TICKETING_PAYMENT_POSTGRES_USER`·`$TICKETING_PAYMENT_POSTGRES_DB`를 사용한다.
- `listener_id`와 `event_type`을 확인하고 해당 발행 앱에서 예외 로그를 찾는다. 주문을 좁힐 때는 `serialized_event::jsonb ->> 'orderId'` 또는 `serialized_event::jsonb ->> 'bookingId'`를 주문 ID와 비교한다. 발행 `id`를 기록해 복구 후 같은 행을 조회한다.
- `status`는 `PUBLISHED`, `PROCESSING`, `FAILED`, `RESUBMITTED`다(Modulith 2.x). 과거 행은 null일 수 있다.

**재처리 절차** (별도 운영 API는 두지 않는다. 결정은 [m6-msa.md](./plans/m6-msa.md) P5)

1. 원인을 먼저 고친다(리스너 버그, 외부 시스템 장애, 설정). 원인이 남은 채 재처리하면 같은 실패가 반복된다.
2. Kafka 장애였다면 기존 데이터와 주소로 복구하고 브로커 요청이 성공하는지 확인한다. 미완료 행을 가진 발행 앱(core 또는 payment)을 재기동한다. 두 앱은 `republish-outstanding-events-on-restart: true`로 기동 시 미완료 발행을 다시 처리한다. 다중 인스턴스에서는 다른 인스턴스의 처리 상황도 확인한다.
3. 같은 발행 `id`에 `completion_date`가 기록됐는지 확인한다. Kafka 외부화는 소비 그룹의 커밋 오프셋이 해당 레코드를 지났는지도 확인한다. 미완료가 남으면 `completion_attempts`와 해당 앱 로그를 다시 본다. core는 미완료 건수와 최고령 지표도 확인한다.

**쿠폰 예약 응답 지연**

결제 요청 전이고 만료되지 않은 예매는 결제 화면에서 `쿠폰 해제`를 누른 뒤 정가로 결제할 수 있다. 해제가 성공하면 예매 상세에서 쿠폰이 비고 결제 예정 금액이 정가로 돌아온다. 운영자는 미완료 발행과 core 로그에서 `CouponReservationRequested`, `CouponReserved`, `CouponRejected`, `CouponReleaseRequested`를 확인한다. 같은 주문과 쿠폰의 `reservationAttemptId`로 관련 발행을 대조한다. 제거 직후 결과 리스너가 예매의 낙관적 락 충돌로 실패했다면 미완료로 남고 위 재처리 절차가 적용된다. 제거한 시도의 늦은 성공은 같은 시도의 해제를 요청하고, 늦은 거절은 예매를 바꾸지 않는다.

**예매·결제 상태 확인**

- 브로커 장애 때 PG 승인이 처리되면 payment는 `SUCCEEDED`를 커밋했더라도 `PaymentSucceeded` 외부화가 payment DB에 미완료로 남을 수 있다. 이때 core는 `PENDING`, 적용 쿠폰은 `RESERVED`일 수 있다. 브로커 복구 후 payment를 재기동한다. 이미 만료 시각이 지난 주문이면 예매 `EXPIRED`, payment와 PG `REFUNDED`, 쿠폰 `ISSUED`, 판매 좌석 기록 없음과 좌석 재선점을 확인한다.
- payment 중단 중 확정 예매를 취소하면 core는 `CANCELLED` 전이와 판매 좌석 삭제를 커밋한다. 커밋 후 같은 좌석을 다시 선점할 수 있고 `RefundRequested`는 Kafka에 적재된다. core의 발행 행이 완료됐고 PG가 `APPROVED`라면 payment를 같은 DB와 브로커, 기존 `ticketing-payment` 그룹으로 기동한다. 해당 레코드까지 소비가 진행된 뒤 payment와 PG가 같은 거래를 `REFUNDED`로 표시하고 쿠폰이 `USED`이며 정산 환불 사본이 생겼는지 확인한다.

**DLT 확인과 재처리**

소비자 처리 예외는 기본적으로 최초 1회와 지수 백오프 재시도 3회(1초부터 시작, 최대 간격 10초) 후 원본 토픽 이름에 `.dlt`를 붙인 토픽으로 보낸다. 역직렬화 오류 등 재시도 불가능한 예외는 바로 DLT로 간다. 발행은 완료됐는데 소비 그룹이 레코드를 지났고 주문이 여전히 `PENDING`이거나 환불을 기다린다면 원본 토픽의 DLT에서 주문 키를 찾는다. `ticketing_kafka_dead_letter_total`의 원본 토픽별 증가에는 전송 시도가 포함되므로 소비 앱 로그와 실제 DLT 레코드를 함께 확인한다. 장애 전 파티션별 끝 오프셋이 있다면 그 지점부터 새 끝 오프셋 직전까지 확인한다.

1. 브로커의 DLT를 별도 소비자 그룹으로 읽고 토픽·파티션·오프셋, 원본 키와 JSON 본문, 예외 헤더를 보관한다. 기존 소비자 그룹의 오프셋은 변경하지 않는다. 예를 들어 단일 토픽은 다음 명령으로 확인한다(출력에 결제 정보가 있으므로 접근을 제한한다).

   ```bash
   docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 \
     --topic ticketing.payment.payment-requested.dlt --from-beginning \
     --property print.key=true --property print.partition=true --property print.offset=true \
     --property print.headers=true --property key.separator=' | '
   ```

2. 실패 원인과 해당 주문의 결제·예매 상태를 조사하고 먼저 수정한다. 이미 처리된 주문이나 순서가 뒤바뀐 이벤트가 있을 수 있으므로 각 건의 재처리 필요성을 판단한다. 소비자의 멱등성은 중복 부작용을 줄이지만 모든 순서 문제를 자동으로 해결하지 않는다.
3. 선택한 레코드만 원본 토픽에 다시 발행한다. Kafka 클라이언트로 **원래 키와 JSON 바이트를 그대로** 전송하고 전송 성공을 확인한다. DLT 전체를 파이프로 원본에 밀어 넣거나 콘솔 출력의 구분자·예외 헤더를 본문에 포함하지 않는다. 역직렬화 오류로 JSON 자체가 잘못됐다면 원본을 보존하고 계약과 실제 상태를 조사해 수정 방안을 별도로 결정한다.
4. 원본 토픽의 소비자 그룹 진행, core 로그, 주문별 결제·예매 상태를 확인한다. 같은 건이 DLT에 다시 추가되지 않았는지도 확인한다. DLT 레코드는 재발행해도 자동 삭제되지 않으므로 보관한 토픽·파티션·오프셋과 처리 결과로 중복 재처리를 방지한다.

**제거된 로컬 리스너의 과거 행**

Kafka 전환 전 결제 요청·환불 요청과 결제 결과 3종의 로컬 리스너가 남긴 미완료 행은 현재 리스너가 없어 재기동해도 처리되지 않는다. 외부화 리스너 행이나 다른 로컬 리스너 행은 대상이 아니다. 이전 구현(`fd05b3d`)의 다섯 `on` 메서드에 명시적 ID가 없어 기본 ID인 클래스·메서드·매개변수 타입을 사용한다. 아래 조회에서 ID·이벤트·본문과 업무 상태를 확인하고 대상 행을 별도 보관한다. 다른 버전에서 ID를 명시한 행은 이 조건에 포함되지 않으므로 별도로 조사한다.

```sql
select id, listener_id, event_type, serialized_event, publication_date, status
from public.event_publication
where completion_date is null
  and listener_id in (
    'io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener.on(io.github.ticketing.payment.PaymentRequested)',
    'io.github.ticketing.payment.adapter.provided.event.RefundRequestListener.on(io.github.ticketing.payment.RefundRequested)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentSucceeded)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentFailed)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentRefunded)'
  )
order by publication_date;
```

행마다 Kafka 외부화 완료 여부와 결제·예매 상태를 대조한다. 과거 로컬 처리 실패가 현재 Kafka 처리 성공을 뜻하지는 않는다. 미처리 업무는 원인과 복구 방안을 먼저 결정한다. 폐기해도 되는 행만 ID를 명시해 트랜잭션에서 삭제하고 건수와 잔여 행을 확인한 뒤 커밋한다. `completion_date`를 채워 성공처럼 위장하지 않는다. 예시는 확인한 ID 하나를 넣어 실행한다.

```sql
begin;
delete from public.event_publication
where id = '<확인한 UUID>' and completion_date is null
  and listener_id in (
    'io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener.on(io.github.ticketing.payment.PaymentRequested)',
    'io.github.ticketing.payment.adapter.provided.event.RefundRequestListener.on(io.github.ticketing.payment.RefundRequested)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentSucceeded)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentFailed)',
    'io.github.ticketing.booking.adapter.provided.event.PaymentResultListener.on(io.github.ticketing.payment.PaymentRefunded)'
  )
returning id, listener_id, event_type;
-- 결과가 확인한 대상과 정확히 같으면 commit; 아니면 rollback;
```

**주의**

- 재처리는 같은 이벤트를 다시 전달한다. 리스너는 멱등해야 하며, 중복으로 부작용이 생기는 리스너는 먼저 고친다.
- 재시작 재발행은 core가 여러 인스턴스일 때 인스턴스마다 실행되어 같은 발행이 겹쳐 처리될 수 있다. 멱등 소비자가 전제다.
- core-batch는 `republish-outstanding-events-on-restart: false`라서 Batch 실행으로는 재처리되지 않는다.
- 행을 직접 `delete`하거나 `completion_date`를 수동으로 채우는 것은 이벤트를 버리는 일이다. 의도적으로 폐기할 때만 하고, 대상 행을 먼저 조회해 확인한다.
- 완료된 행은 계속 쌓인다. 정리(삭제) 정책은 아직 없고, 필요 시점은 [m6-msa.md](./plans/m6-msa.md) 후속 항목에 적었다.

### 3.8 알림이 울렸다

Alertmanager에서 알림 이름과 라벨을 확인하고 `Ticketing Overview`에서 대응 패널을 연다. 앱은 `service` 또는 `application`, Kafka는 `consumergroup`과 `topic`, Batch는 `exported_job`으로 대상을 좁힌다.

| 알림 | 발화 조건 | 먼저 볼 패널 | 대응 |
|---|---|---|---|
| `AppDown` | 앱 스크레이프 실패가 1분 지속 | 앱 상태 | 해당 앱의 헬스·기동 로그와 Prometheus의 Targets를 확인한다. [앱 기동 진단](#31-앱이-뜨지-않는다) |
| `HighErrorRate` | 앱의 HTTP 5xx 비율이 5% 초과로 5분 지속 | HTTP 5xx 비율 | 같은 시간대의 요청 수와 앱 로그를 보고 오류 경로를 좁힌다. 결제 요청이면 [결제 장애 진단](#32-결제가-pending에서-넘어가지-않는다)을 따른다. |
| `EventPublicationStuck` | 최고령 미완료 발행이 300초를 초과한 상태가 5분 지속 | 최고령 미완료 이벤트 경과 | 발행 앱 DB에서 미완료 행을 찾고 리스너나 Kafka 전송 실패를 복구한다. [미완료 이벤트 대응](#37-미완료-이벤트-발행이-쌓인다) |
| `KafkaConsumerLagHigh` | 그룹·토픽의 전체 파티션 lag가 1,000건 초과로 5분 지속 | 컨슈머 그룹 lag | 컨슈머 기동 상태와 소비 예외를 확인하고 재기동한 뒤 lag가 줄어드는지 본다. [결제 장애 진단](#32-결제가-pending에서-넘어가지-않는다) |
| `KafkaDeadLetterIncreased` | 최근 10분 동안 DLT 토픽의 끝 오프셋 증가 | 최근 10분 DLT 증가 | "토픽별 DLT 적재"와 컨슈머 로그에서 반복 실패 원인을 먼저 찾고, 재처리 전에 해당 결제·예매 상태를 대조한다. [결제 장애 진단](#32-결제가-pending에서-넘어가지-않는다) |
| `BatchJobFailed` | Job의 최근 종료 실행이 `FAILED` 또는 `ABANDONED` | Job별 마지막 상태 | 해당 배치의 실패 단계와 파라미터를 찾고 원인을 수정한다. 실패 실행의 재시작 절차를 따른다. [Batch 실행](#4-batch-실행) |
| `HikariPoolExhausted` | Hikari 연결 대기가 2분 지속 | Hikari 연결 대기 | 같은 시간대의 PostgreSQL 연결 수와 긴 쿼리·트랜잭션, 앱 로그의 연결 타임아웃을 확인한다. [DB 상태 확인](#5-데이터-확인) |

## 4. Batch 실행

Job은 업무별 배치 앱에서 하나씩 실행한다. 규칙과 파라미터의 기준은 [batch.md](./architecture/batch.md)다.

| Job | 파라미터 | 언제 |
|---|---|---|
| `dailySettlementJob` | `targetDate`, `run.id` | 일별 정산(`settlement-batch`, `settlement_db`) |
| `expirePendingBookingsJob` | `run.id` | 미결제 예매 만료 처리 |
| `reconcileSeatSnapshotJob` | `run.id` | 좌석 스냅샷 보정 |
| `releaseStaleCouponReservationsJob` | `run.id` | 기한 지난 RESERVED 쿠폰 복원 |
| `paymentReconciliationJob` | `targetDate`, `run.id` | 결제 대사(`payment-batch`, `payment_db`) |

```bash
./gradlew :settlement-batch:bootRun --args='--spring.batch.job.name=dailySettlementJob targetDate=2026-11-10,java.time.LocalDate run.id=1,java.lang.Long'
```

- 완료된 같은 날짜를 다시 계산하려면 `run.id`를 새 값으로 바꾼다. 결과는 upsert 또는 날짜 단위 재적재라서 같다.
- 실패한 실행은 같은 `targetDate`와 `run.id`로 재시작한다. 새 `run.id`를 쓰면 새 실행이 된다.
- 정산일은 Asia/Seoul 기준이다. 늦게 도착한 이벤트가 영향을 준 날짜는 소비가 끝난 뒤 새 `run.id`로 다시 계산한다. [배치 전 점검과 복구](#복귀와-지연-이벤트)
- 결제 대사는 `java -jar payment-batch/build/libs/payment-batch.jar --spring.batch.job.name=paymentReconciliationJob targetDate=<Asia/Seoul 날짜>`로 실행한다([payment-batch 지침](rules/backend-payment-batch.md)).
- 실행 이력은 core DB, `payment_db`, `settlement_db` 각자의 `batch` 스키마에서 조회한다. 분리 전 정산 실행 이력은 core DB에 보존한다.

```bash
docker compose exec postgres psql -U "$TICKETING_POSTGRES_USER" -d "$TICKETING_POSTGRES_DB" \
  -c 'select job_instance_id, job_name from batch.batch_job_instance order by 1 desc limit 10;'
```

## 5. 데이터 확인

- core는 core DB의 `member`, `catalog`, `booking`, `coupon`, `audit`, `batch`를 쓴다. 결제는 `payment_db`의 `payment`, `batch`를, 정산은 `settlement_db`의 `settlement`, `batch`를 쓴다. core DB의 옛 `settlement` 스키마는 이전과 복귀를 위해 보존한다. 디버깅용 조회도 한 스키마씩 한다.
- 구조는 [erd.md](./architecture/erd.md)를 본다.
- 스키마 변경은 Flyway 마이그레이션으로만 한다. 적용된 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.

## 6. 테스트·검증 실패 대응

- 실패는 실패한 그대로 보고한다. 테스트를 지우거나 `@Disabled`로 우회하지 않는다.
- 로그는 전체를 출력하지 않고 `rg -n -m 20 '오류 패턴' 로그경로`로 위치를 좁힌다.
- Gradle 테스트 결과 파일이 깨졌다면 test와 check를 동시에 돌렸는지 확인하고 순차로 다시 실행한다.
- E2E 실패 시 `build/e2e/logs/`의 앱별 로그를 보고, `--keep`으로 스택을 남겨 재현한다.
- E2E 기본 앱 포트는 18000, 18080, 18081, 18082, 18085, 18086, 18090과 13000(`--web`)이다. 동시에 개발 스택을 유지하려면 별도 작업 디렉터리에 E2E용 `.env`를 두고, 고유한 Compose 프로젝트와 브리지 이름, 별도 인프라 포트, 앱 포트 오프셋을 지정한다.

  ```bash
  export COMPOSE_PROJECT_NAME=ticketing-e2e TICKETING_BRIDGE_NAME=br-tkt-e2e
  export TICKETING_POSTGRES_PORT=25432 TICKETING_REDIS_PORT=26379 TICKETING_KAFKA_PORT=29092 TICKETING_OBJECT_STORAGE_PORT=29010
  export E2E_APP_PORT_OFFSET=10000
  ./scripts/e2e/run.sh
  ./scripts/e2e/run.sh --web
  ```
- Saga 장애 재현은 위와 같이 앱과 인프라가 격리된 Linux 작업 디렉터리에서 `./scripts/e2e/run.sh --saga-recovery`를 실행한다. 호출 환경에 기본 프로젝트와 다른 `COMPOSE_PROJECT_NAME`을 명시하고 고유 브리지와 별도 포트·볼륨을 사용한다. `--web`과 함께 지정하면 종료 코드 2를 반환한다. `--tests '*SagaRecoveryE2eTests'`로 장애 시나리오만 실행할 수 있다. 실행기는 `--keep`에서도 일시 중지한 Kafka를 재개하며, payment의 재기동 로그는 `build/e2e/logs/saga-*/payment-restart-*.log`에 남긴다.
- Testcontainers 컨테이너가 남아 있으면 `docker ps`로 확인하고 Ryuk이 정리하지 못한 것만 지운다.

## 7. 변경 시 확인 사항

| 바꾼 것 | 확인 |
|---|---|
| core 모듈 간 의존 | Modulith 구조 검증, `./gradlew :core:modulithDocs` |
| payment 데이터 이전 스크립트 | `./scripts/db/test-migrate-payment-data.sh`(임시 DB, 실제 `payment_db`는 건드리지 않는다) |
| core·gateway·realtime 흐름 | `./scripts/e2e/run.sh` |
| web | `./scripts/agent/test/web-check.sh`, 필요하면 `./scripts/e2e/run.sh --web` |
| Kotlin | `ktlintCheck detekt` |
| 문서의 경로·제목·링크 | `./scripts/agent/docs/check-docs.sh` |

설계와 다르게 가야 하면 먼저 사용자에게 알리고, 합의되면 `architecture.md`와 새 ADR을 같이 고친다.

## 8. 관측 스택 띄우기와 부하 테스트 실행

관측 스택은 기본 Compose 위에 `compose.observability.yaml`을 겹쳐 띄운다.

```bash
docker compose -f compose.yaml -f compose.observability.yaml up -d --wait   # 관측 스택 기동
docker compose -f compose.yaml -f compose.observability.yaml down          # 종료 (데이터 유지)
```

| 도구 | 주소 | 용도 |
|---|---|---|
| Grafana | http://localhost:13001 | 대시보드 조회. 익명 Admin으로 열린다 |
| Grafana · Ticketing Overview | http://localhost:13001/d/ticketing-overview | 앱 상태, 트래픽, 비동기 흐름, Batch, 자원 요약 |
| Grafana · Ticketing Load Test | http://localhost:13001/d/ticketing-loadtest | 부하 측정과 상세 JVM 지표 |
| Grafana · Ticketing · Gatling hot-seats 300ms | http://localhost:13001/d/ticketing-hot-seats-gatling | Gatling 전용 측정 지표 |
| Prometheus | http://localhost:19090 | 지표 조회. k6 지표도 remote-write로 들어온다 |
| Alertmanager | http://localhost:19093 | 발화 알림 조회. Grafana에는 Alertmanager 데이터 소스로 연결된다 |
| Tempo | http://localhost:13200 | trace 조회. 보통은 Grafana Explore에서 본다 |

- M6 이전 측정 기록의 `Ticketing Overview`는 지금의 `Ticketing Load Test`다.
- 기본 설정은 Alertmanager UI에서 알림을 확인한다. 웹훅을 보내려면 Compose에 넘기는 환경 변수 또는 로컬 `.env`에 `ALERTMANAGER_WEBHOOK_URL`을 설정하고 `docker compose -f compose.yaml -f compose.observability.yaml up -d --wait alertmanager`로 반영한다. 수신처는 Alertmanager 웹훅 JSON을 받을 수 있어야 한다. URL은 저장소에 커밋하지 않는다.
- 앱은 span을 기본으로 내보내지 않는다. 켜려면 앱을 띄울 때 `TRACING_EXPORT_ENABLED=true`를 주고, 비율은 `TRACING_SAMPLING_PROBABILITY`(기본 1.0)로 정한다. 내보내는 주소는 `OTLP_TRACES_ENDPOINT`(기본 `http://localhost:14318/v1/traces`)다. `scripts/loadtest/run.sh --observability`는 이 값을 `true`와 `0.1`로 넣는다.
- Prometheus는 [앱 포트](#1-구성-요소와-의존)의 `/actuator/prometheus`와 Kafka·PostgreSQL·Redis exporter에서 지표를 수집한다. 별도 프로세스로 실행한 Batch의 Job 상태는 같은 DB를 사용하는 상시 앱에서 노출한다.

### 8.1 알림 발화와 해소 확인

별도 개발 환경에서 관측 스택과 core·payment·fake-pg를 기동하고 아래 절차를 실행한다.

1. payment를 별도 터미널에서 실행해 테스트 결제를 한 건 처리한다. 다음 명령으로 `ticketing-payment` 그룹이 오프셋을 커밋했고 lag가 0인지 확인한다. 그 터미널에서 Ctrl+C로 payment를 종료하고, 1분 이상 지난 뒤 Alertmanager에서 `service=payment`인 `AppDown`을 확인한다.

   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --describe --group ticketing-payment
   ```

2. payment가 정지한 동안 개발용 예매로 결제 요청 이벤트를 발생시킨다. 위 명령과 "컨슈머 그룹 lag" 패널에서 그룹·토픽별 파티션 합계가 1,000건을 초과하는 상태를 5분 유지해 `KafkaConsumerLagHigh`를 확인한다. payment를 재기동해 `AppDown`이 해소되고, 소비가 따라잡으면 lag 알림도 해소되는지 본다.
3. payment와 fake-pg는 켜 둔다. 개발 DB 설정을 불러온 셸에서 새 테스트 날짜·실행 ID를 골라 결제 대사를 실행한다. 첫 명령에서는 Batch만 연결할 수 없는 PG 주소를 사용해 `fetchPgTransactionsStep`에서 실패한다. `Job별 마지막 상태`와 Alertmanager에서 `paymentReconciliationJob`의 실패를 확인한다.

   ```bash
   batch_args='--spring.batch.job.name=paymentReconciliationJob targetDate=2026-11-10,java.time.LocalDate run.id=99,java.lang.Long'
   FAKE_PG_BASE_URL=http://127.0.0.1:1 ./gradlew :payment-batch:bootRun --args="$batch_args"
   ```

4. 같은 인자로 `./gradlew :payment-batch:bootRun --args="$batch_args"`를 실행한다. 개발 환경의 정상 PG 주소를 사용한 재실행이 끝나면 `Job별 마지막 성공 시각`이 갱신되고 `BatchJobFailed`가 해소되는지 본다.

### 8.2 부하 테스트

부하 테스트는 `scripts/loadtest/run.sh`로 실행한다. 필요한 것은 `.env`, 실행 중인 Docker, 비어 있는 18000·18080·18081·18090 포트다. k6 바이너리(`build/loadtest/k6`)는 실행 전에 `build-k6.sh`로 빌드된다. smoke는 없을 때만 빌드한다.

```bash
./scripts/loadtest/run.sh --scenario smoke                                   # 연결과 시나리오 점검
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability    # 기준 측정 A (3회)
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability      # 기준 측정 B (3회)
./scripts/loadtest/run.sh --scenario hot-seats --failure-inject --observability  # 실패 주입 (fake-pg 실패 10%, 웹훅 중복 30%)
./scripts/loadtest/run.sh --scenario queue-surge --jfr                       # JFR로 pinning 확인
./scripts/loadtest/run.sh --scenario hot-seats --warmup --runs 3 --observability   # 워밍업 5회 후 측정(콜드와 별도 계열)
./scripts/loadtest/run.sh --scenario seat-conflict-refund --observability     # 선점 키 유실 주입, 보상·환불 정합성 검증
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability --no-scouter   # Scouter 없이 측정
```

- 주요 옵션: `--runs N`(반복 횟수), `--vus N`, `--k6-cpus 0-3`·`--app-cpus 4-15`(CPU 분리), `--keep`(앱만 정리하고 Compose 인프라는 남긴다), `-h`(전체 옵션).
- 결과는 `build/loadtest/runs/<타임스탬프>-<시나리오>/<N>/`에 저장된다. `k6-summary.json`, `k6.log`, `prometheus.json`, 앱별 `core.log`·`realtime.log`·`gateway.log`·`fake-pg.log`가 있다. git에는 넣지 않는다.
- fake-pg는 러너가 호스트 jar로 띄우므로 18090을 다른 프로세스(`scripts/dev/run.sh --fake-pg` 등)가 점유하지 않게 한다. 결과 해석은 [M5 최종 보고서](performance/m5/11-step8-final-report.md)를 따른다.
- 부하 테스트는 `build`·`check`에 포함되지 않는다. Gradle 작업과 동시에 실행하지 않는다. 측정은 다른 검사가 끝난 뒤 단독으로 돌린다.

정합성 검증이 실패하면(`정합성 위반`, 종료 코드 1) 다음을 본다.

1. 실행 디렉터리의 `integrity.log`(검증 스크립트 출력)와 `integrity.json`(위반 목록과 상태별 예매 수). 원본은 `build/loadtest/integrity-<scheduleId>.json`이다.
2. 같은 실행의 `core.log`. 좌석 확정·결제 웹훅 처리 중 예외와 보상 실패를 찾는다. 로그는 `rg -n -m 20 'ERROR|Exception' 로그경로`로 좁힌다.
3. 중복 판매(같은 좌석이 두 번 확정)는 DB 유니크 제약이 막아야 하는 사건이다. 위반이 있으면 측정 결과로 넘기지 말고 원인을 먼저 찾는다.

## 9. 결제 DB 이전 (core DB payment 스키마 → payment_db)

[ADR 0040](adr/0040-payment-separate-database-same-instance.md)의 이전 절차 2~4단계를 수행한다. 무중단 이중 쓰기는 하지 않고 짧은 점검 창을 둔다. 이미 대상 DB(기본 `payment_db`)에 데이터가 있으면(분리 후 새로 쌓인 데이터 포함) 스크립트가 덮어쓰지 않고 중단한다. 이때는 이전이 필요 없는지부터 판단한다.

대상 테이블은 `payment.payment`, `payment.webhook_event`, `payment.pg_transaction_snapshot`, `payment.reconciliation_mismatch` 네 개다. Flyway 이력과 `event_publication`은 옮기지 않는다.

이전 스크립트의 원본은 옛 `payment` 스키마가 남아 있는 V18 적용 전 core DB다. 분리와 전환 검증을 먼저 배포하고, 10단계의 백업을 마친 후 V18이 포함된 core와 core-batch를 배포한다.

**대상 DB는 한 번만 정한다.** payment 앱과 payment-batch는 환경 변수 `TICKETING_PAYMENT_POSTGRES_DB`(기본 `payment_db`)가 가리키는 DB에 접속하고, 이전 스크립트의 `--target-db` 기본값도 `.env`의 같은 변수다. 다른 이름의 DB로 이전하려면 `--target-db`만 바꾸지 말고 `.env`의 `TICKETING_PAYMENT_POSTGRES_DB`를 그 이름으로 바꾼 뒤 아래 모든 단계(DB 준비, Flyway, 이전, 재대조, 앱 기동, 복구)를 같은 값으로 수행한다. `--target-db`만 다르면 이전은 성공해도 앱은 기본 DB를 봐서 기존 결제가 조회되지 않는다. 이 절의 명령은 `TARGET_DB`를 쓴다.

```bash
set -a; source .env; set +a                  # 앱·스크립트가 읽는 값과 같은 값을 쓰기 위해
TARGET_DB="${TICKETING_PAYMENT_POSTGRES_DB:-payment_db}"
echo "$TARGET_DB"                            # 이후 모든 단계가 이 DB를 가리킨다
```

**스크립트가 하는 일** (`scripts/db/migrate-payment-data.sh [--verify-only] [--source-db 이름] [--target-db 이름]`)

1. 전제 검사(빠른 실패용): 대상 4테이블이 비어 있는지, 원본·대상의 `event_publication`에 미완료(`completion_date IS NULL`) 행이 없는지 본다.
2. 원본에서 `REPEATABLE READ` 세션을 열어 스냅샷을 내보내고, 그 스냅샷으로 `pg_dump --data-only`와 원본의 미완료 이벤트·체크섬을 같은 시점에 읽는다. 원본 시퀀스의 다음 발급 값은 MVCC 스냅샷에 묶이지 않고 조회 시점의 값이므로 점검 창의 쓰기 중지에 의존한다. 덤프는 컨테이너 임시 파일에 받고 정상 종료를 확인한 뒤에만 적재한다.
3. 대상에서 `READ COMMITTED`를 명시한 한 트랜잭션을 연다(대상 DB·역할의 기본 격리 수준이 `REPEATABLE READ`여도 잠금 뒤 재검사가 최신 커밋을 본다). advisory lock으로 같은 대상을 향한 실행을 직렬화하고, 대상 4테이블과 `event_publication`을 쓰기 차단 모드(`EXCLUSIVE`)로 잠근 뒤 **비어 있음과 미완료 이벤트를 다시 검사**한다. 데이터를 적재하고 `ALTER SEQUENCE payment.payment_id_seq RESTART WITH max(max(id)+1, 원본 다음 발급 값)`로 시퀀스를 맞춘다. 덤프의 `setval`은 트랜잭션으로 되돌릴 수 없어 적용하지 않는다.
4. **COMMIT 전에** 대상 건수·금액 합계·상태 분포·행 해시(행 전체를 기본 키 순으로 이은 텍스트의 `md5`)를 스냅샷 시점의 원본 값과 비교하고, 시퀀스 다음 발급 값이 최대 id보다 크고 원본 값 이상인지 본다. 하나라도 다르면 롤백한다(시퀀스 변경도 함께 되돌아간다).
5. 커밋 뒤 새 세션에서 같은 대조를 한 번 더 한다(커밋 가시성 확인).

덤프나 적재 SQL이 중간에 실패하면 `COMMIT`에 이르지 못해 롤백된다. 적재 `psql`이 비정상 종료하면 스크립트는 **먼저 대상의 적재 트랜잭션이 끝나기를 기다린 뒤**(같은 advisory lock을 새 세션에서 얻을 때까지 대기. 대기 시간은 기본 30초) 확정된 상태를 읽어 구분해 출력한다. 서버의 적재 트랜잭션이 아직 진행 중일 때 읽으면 커밋 전 데이터가 0건으로 보이기 때문이다.

- 적재 종료 뒤 대상 4테이블과 시퀀스가 적재 전 상태와 같다 → "반영된 변경이 없음을 확인했습니다". 원인을 고치고 바로 다시 실행하면 된다.
- 4테이블에 데이터가 있고 원본 스냅샷과 일치한다 → "커밋 응답만 잃은 것으로 보입니다". **다시 실행하지 않는다.**
- 4테이블은 비어 있는데 시퀀스가 적재 전과 다르다 → 원본 4테이블이 비어 있고 시퀀스만 앞서 있던 적재가 커밋된 경우다("시퀀스만 반영하는 적재가 커밋되고 응답만 잃은 것"). 다시 실행하지 않는다.
- 데이터가 있으나 원본과 다르거나 종료를 시간 안에 확인하지 못했다(잠금 대기 시간 초과·접속 실패) → "커밋 결과를 확인할 수 없는 오류입니다". 아래 "커밋 결과를 알 수 없을 때"를 따른다.

비밀번호는 컨테이너 환경 변수로만 쓰며 명령행·출력에 남기지 않는다.

**사전 준비 (점검 창 전)**

1. `.env`에 `TICKETING_PAYMENT_POSTGRES_*`가 있고 `./scripts/db/ensure-payment-db.sh`로 `$TARGET_DB`와 전용 계정이 준비돼 있다(이 스크립트도 같은 환경 변수를 쓴다).
2. `$TARGET_DB`에 payment 앱의 Flyway 마이그레이션(V1~V3: `payment` 스키마, `event_publication`, `batch`)을 적용한다. 일반 payment 앱은 점검 창 전에 띄우지 않는다. 기동하면 Kafka 소비자 그룹 `ticketing-payment`에 합류해 결제·환불 이벤트를 비어 있는 DB에서 처리하기 때문이다. 대신 `payment-batch`를 Job 없이 띄운다. 같은 셸(위에서 `.env`를 읽은 셸)에서 실행해 대상 DB 변수를 그대로 쓴다.

   ```bash
   ./gradlew :payment-batch:bootJar
   TICKETING_PAYMENT_POSTGRES_DB="$TARGET_DB" java -jar payment-batch/build/libs/payment-batch.jar --spring.batch.job.enabled=false
   ```

   `batch` 프로필(`payment/src/main/resources/application-batch.yml`)이 웹 서버를 끄고(`web-application-type: none`), Kafka 리스너 자동 기동을 끄고(`spring.kafka.listener.auto-startup: false`), 미완료 이벤트 재발행을 끈다(`republish-outstanding-events-on-restart: false`). 예열 러너 같은 웹 전용 빈도 만들지 않는다. `--spring.batch.job.enabled=false`로 어떤 Job도 실행하지 않으므로 Flyway를 적용하고 종료한다. 임시 DB로 확인한 결과는 `./scripts/db/test-migrate-payment-data.sh --flyway-boot`가 재현한다. 이미 적용돼 있으면 건너뛴다. 확인은 같은 DB에서 한다.

   ```bash
   docker compose exec -T postgres psql -X -At -U "$TICKETING_POSTGRES_USER" -d "$TARGET_DB" \
     -c "select current_database(), count(*) from public.flyway_schema_history where success"   # 대상 DB 이름과 3건
   ```
3. 이전 스크립트를 확인하려면 V18 적용 전 core DB의 사본과 대상 스키마가 준비된 별도 환경에서 `./scripts/db/test-migrate-payment-data.sh`를 실행한다. 기본 모드는 원본 스키마를 복제하고, `--full`은 원본 데이터도 복제해 대용량 경로까지 확인한다.

**점검 창 절차**

1. **신규 유입 차단**: web과 gateway를 내려 사용자 요청을 막는다. 이때 core(분리 전 구성), fake-pg는 그대로 둔다.
2. **기존 구성에서 배출 확인**: 진행 중인 결제·환불이 끝나고 이벤트와 웹훅이 모두 비워질 때까지 기다린다. 아래를 **모두** 확인한다.
   - 결제·환불 처리 중 건이 없다: `select status, count(*) from payment.payment group by status`에서 `REQUESTED`, `REFUND_REQUESTED`가 없다. 단 이 값만으로 충분하지 않다. 결제 요청 이벤트가 발행돼 완료 처리된 뒤 소비 단계에서 실패해 DLT로 갔다면 결제 행이 아예 만들어지지 않으므로 `REQUESTED` 행도 lag도 미완료 발행도 0인 채로 요청이 처리되지 않을 수 있다. 그래서 다음 DLT 확인이 필요하다.
   - **DLT를 기준선으로 확인한다.** 결제·환불 계약 이벤트 5개 토픽의 `.dlt` 끝 오프셋을 읽어 기록한다. 레코드가 있으면 [3.7의 DLT 확인과 재처리](#37-미완료-이벤트-발행이-쌓인다)에 따라 건별로 재처리하거나 폐기·보류를 결정하고 그 결과(주문별 결제·예매 상태)를 확인한다. 처리한 레코드는 DLT에서 자동 삭제되지 않으므로 결정을 끝낸 시점의 끝 오프셋을 **기준선**으로 기록한다. 결정하지 못한 건이 있으면 전환하지 않는다.

     ```bash
     for t in payment-requested refund-requested payment-succeeded payment-failed payment-refunded; do
       docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-get-offsets.sh \
         --bootstrap-server localhost:19092 --topic-partitions "ticketing.payment.$t.dlt"   # 토픽:파티션:끝 오프셋
     done
     ```
   - **fake-pg의 웹훅 발송이 끝났다.** fake-pg는 접수한 거래의 웹훅을 메모리 스케줄러에 예약하고, 예약된 발송은 Kafka lag이나 `event_publication`에 나타나지 않는다. 발송은 거래마다 최초 시도를 포함해 **최대 3번**(`fake-pg.webhook.max-attempts`)이고 재시도 간격은 1초다. 시도 하나는 연결 타임아웃 3초에 HTTP 타임아웃 5초까지 걸릴 수 있다. 첫 시도 전 지연은 기본 500ms이고 DELAYED 시나리오는 6분(`delayed-webhook-delay`)이다. 중복 웹훅 시나리오(`duplicate-count`)는 같은 본문을 여러 번 예약한다. 그러므로 마지막 결제·환불 요청 이후 **지연(최대 6분) + 시도 3회 × 8초 + 재시도 간격 2초** 이상(여유를 두어 8분)이 지난 뒤, 로그의 마지막 줄이 늦게 나올 수 있다고 가정하고 아래를 거래별로 확인한다. 로그가 조용하다는 사실만으로 소진을 판정하지 않는다.
     - `build/dev/logs/fake-pg.log`에서 접수한 거래(transactionId)마다 `웹훅 발송 완료` 또는 `웹훅 발송 포기`가 있고 `웹훅 연결 실패`만 마지막으로 남은 거래가 없다.
     - `웹훅 발송 포기`가 있는 거래는 payment 쪽 상태(`select status from payment.payment where pg_transaction_id = '<거래 ID>'`)를 보고 종결(`SUCCEEDED`, `FAILED`, `REFUNDED`)됐는지, 아니면 대사·수동 처리로 보류할지 결정한다.
   - Kafka 소비자 그룹 lag이 0이다.

     ```bash
     for group in ticketing-payment ticketing-booking; do
       docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-consumer-groups.sh \
         --bootstrap-server localhost:19092 --describe --group "$group"
     done
     ```
   - core DB와 `$TARGET_DB`의 `public.event_publication`에서 `completion_date IS NULL` 행이 0건이다. 남으면 [3.7](#37-미완료-이벤트-발행이-쌓인다)로 처리한다.
   - **정지 직전 최종 재확인.** 위 웹훅 대기와 소비 재시도(최초 1회와 지수 백오프 3회, 1초부터 시작)가 모두 끝난 뒤, 기존 앱을 내리기 직전에 다음을 다시 확인한다. 지연된 웹훅이나 재처리로 생긴 이벤트가 늦게 DLT로 갈 수 있기 때문이다. (1) 위 `for` 명령으로 다섯 DLT의 파티션별 끝 오프셋이 **기준선과 같다**. (2) 소비자 그룹 lag이 0이다. (3) 두 DB의 미완료 `event_publication`이 0건이다. (4) fake-pg 로그에 마지막 확인 이후 새 `웹훅 발송` 줄이 없다. 하나라도 달라졌으면 그 건을 처리·결정해 기준선을 갱신하고 **2단계의 웹훅 대기부터 다시 수행한다.** 모두 같을 때만 3단계로 간다.
3. **쓰기 중지**: core, payment 앱(있으면), core-batch, payment-batch를 멈추고 실행 중인 Batch가 없는지 확인한다. **fake-pg는 멈추지 않는다.** 아래 한계를 본다.
4. **이전**: 저장소 루트에서 위에서 정한 대상 DB를 명시해 실행한다.

   ```bash
   ./scripts/db/migrate-payment-data.sh --target-db "$TARGET_DB"
   ```
5. **대조 확인**은 원본과 대상의 쓰기가 멈춰 있을 때 한다. 스크립트가 커밋 전·후에 대조해 일치하면 `대조 일치`와 함께 종료 코드 0을 낸다. 대상 DB 이름, 실행 시각과 대조 결과를 10단계에서 확인할 수 있도록 배포 기록에 남긴다. 대조를 다시 해야 하면 유입을 재개하기 전에 같은 대상을 지정한다. 원본 시퀀스는 조회 시점 값이므로 쓰기 중지 뒤에 읽어야 한다.

   ```bash
   ./scripts/db/migrate-payment-data.sh --verify-only --target-db "$TARGET_DB"
   ```
6. **새 구성 기동(유입은 아직 닫혀 있다)**. 대조가 일치하면 payment 앱을 **같은 셸**(`TICKETING_PAYMENT_POSTGRES_DB`가 `$TARGET_DB`와 같은 상태)에서 일반 모드로 띄운다. 결제 구현이 분리된 V18 적용 전 core 빌드를 올린다. **gateway와 web은 아직 올리지 않는다.**
7. **fake-pg 웹훅 목적지 확인(유입을 열기 전)**: 실행 중인 fake-pg가 새 payment 주소(`http://localhost:18085/api/v1/payments/webhooks/fake-pg`)로 웹훅을 보내는지 확인한다. 설정은 환경 변수 `FAKE_PG_WEBHOOK_URL`이다(비밀 값인 `FAKE_PG_WEBHOOK_SECRET`은 출력하지 않는다).

   ```bash
   tr '\0' '\n' < "/proc/$(pgrep -f 'fake-pg.jar' | head -1)/environ" | grep '^FAKE_PG_WEBHOOK_URL='   # 값이 payment(18085)여야 한다
   ```

   변수가 없으면 jar의 기본값을 쓴다. 기본값은 `fake-pg/src/main/resources/application.yml`에서 payment 앱(18085)이다(분리 전에 빌드한 jar는 core 18080일 수 있으므로 같은 커밋의 jar인지 확인한다). 목적지가 다르면 **유입이 닫힌 지금** fake-pg를 올바른 값으로 재기동하고 기동 확인(`curl localhost:18090/actuator/health`)과 같은 확인 명령으로 목적지를 다시 확인한 뒤에만 다음으로 간다. 재기동하면 아래 한계대로 기존 거래와 예약이 사라진다. 이 확인이 끝나기 전에는 gateway·web을 올리지 않는다. 열린 상태에서 접수한 신규 결제의 웹훅이 옛 주소로 가거나 재기동으로 신규 거래가 사라질 수 있기 때문이다.
8. **유입 재개**: 목적지 확인이 끝나면 gateway, web을 올려 사용자 요청을 받는다.
9. **전환 후 검증**(아래).
10. **옛 스키마 제거**는 별도 배포에서 진행한다. V18이 포함된 core나 core-batch가 기동하면 Flyway가 스키마를 제거하므로 아래 확인을 기동 전에 마친다.

    - 동일한 대상 DB에 대해 5단계의 `대조 일치` 기록과 9단계의 결제·환불 검증 결과를 확인한다. 유입 재개 후에는 대상 DB에 새로운 거래가 쌓이므로 전환 전에 남긴 대조 기록을 사용한다.
    - core DB에서 `pg_dump`의 `--format=custom --schema=payment` 옵션으로 옛 스키마와 데이터를 백업한다. 백업 파일을 `pg_restore --exit-on-error`로 빈 검증 DB에 복원해 네 테이블과 건수가 원본과 일치하는지 확인한다. 백업에 대한 접근을 제한하고, 백업 위치·복원 확인 결과·보존 종료 시점을 배포 기록에 남긴다.
    - V18이 포함된 core와 core-batch를 배포한 뒤 `information_schema.schemata`에서 core DB의 `payment` 스키마가 사라졌고 `$TARGET_DB`의 `payment` 스키마가 남아 있는지 각각 확인한다.

**커밋 결과를 알 수 없을 때**

적재 `psql`이 `커밋 결과를 확인할 수 없는 오류입니다`로 끝나거나 위 "커밋 응답만 잃은 것으로 보입니다" 계열로 끝나면 이미 커밋됐을 수 있다(서버가 `COMMIT`을 처리한 뒤 연결이 끊기거나 프로세스가 죽은 경우, 또는 서버의 적재 트랜잭션이 아직 끝나지 않은 경우). 쓰기 차단을 유지하고 다음 순서로 확인한 뒤 전환·재시도·복구를 결정한다. 4테이블 건수만으로 커밋 여부를 판단하지 않는다(원본이 비어 있으면 적재는 시퀀스만 바꿔 커밋한다).

1. 대상 DB의 적재 트랜잭션이 끝났는지 확인한다. 대상에 아직 진행 중인 세션이 없어야 한다.

   ```bash
   docker compose exec -T postgres psql -X -At -U "$TICKETING_POSTGRES_USER" -d "$TARGET_DB" -c "
     select pid, state, wait_event_type, left(query, 60) from pg_stat_activity
     where datname = current_database() and pid <> pg_backend_pid() and state <> 'idle'"
   ```

   행이 남아 있으면 끝나기를 기다린 뒤 다시 본다. 끝나지 않으면 4테이블·시퀀스 판단을 보류하고 원인을 조사한다.
2. 대상 DB 이름과 네 테이블의 건수, 시퀀스 상태를 읽는다. `current_database()`가 이전 대상(`$TARGET_DB`)과 같아야 한다.

   ```bash
   docker compose exec -T postgres psql -X -At -U "$TICKETING_POSTGRES_USER" -d "$TARGET_DB" -c "
     select current_database(),
            (select count(*) from payment.payment), (select count(*) from payment.webhook_event),
            (select count(*) from payment.pg_transaction_snapshot), (select count(*) from payment.reconciliation_mismatch),
            (select last_value || ',' || is_called from payment.payment_id_seq)"
   ```
3. 읽기 전용으로 원본과 대조한다: `./scripts/db/migrate-payment-data.sh --verify-only --target-db "$TARGET_DB"`. 시퀀스 다음 발급 값(`is_called`가 `t`면 `last_value + 1`, `f`면 `last_value`)이 원본의 다음 발급 값 이상인지도 이 대조가 본다.
4. 판단
   - 대조가 일치한다 → 커밋은 끝난 것이다(원본이 비어 있어 시퀀스만 반영된 경우 포함). 다시 실행하지 말고 6단계(새 구성 기동)로 진행한다.
   - 4테이블이 0건이고 시퀀스도 이전 상태(적재 전 값. 새 대상은 `1,f`)다 → 반영된 변경이 없다. 원인을 고쳐 다시 실행한다.
   - 데이터가 있으나 대조가 다르거나 시퀀스 상태를 판별할 수 없다 → 다른 세션의 쓰기이거나 일부만 반영된 상태일 수 있다. payment 앱을 띄우지 말고 원인을 조사한 뒤 아래 복구 절차로 비울지 결정한다.

**커밋 뒤에만 가능한 검사와 복구 범위**

- 스크립트는 `COMMIT` 전에 건수·체크섬·시퀀스를 대조해 어긋나면 롤백한다. 커밋 뒤에는 새 세션에서 다시 대조해 커밋된 결과가 읽히는지만 확인한다. 이 단계에서 실패하면 이미 커밋된 상태이므로 롤백되지 않는다.
- 복구: payment 앱을 띄우지 않은 채 대상 DB의 네 테이블이 이번 이전으로 채워진 것뿐인지 확인한다(스크립트가 비어 있음을 검사했으므로 그 사이 다른 쓰기가 없었어야 한다). 비우기 전에 **같은 접속에서 `current_database()`가 이전 대상과 같은지 먼저 확인**한다. 다른 이름이면 중단한다.

  ```bash
  EXPECTED_DB='<이전 스크립트 출력의 "대상(...)"에 찍힌 DB 이름>'   # 예: EXPECTED_DB='payment_db'
  docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$TICKETING_POSTGRES_USER" -d "$TARGET_DB" -c "
    DO \$\$ BEGIN
      IF current_database() <> '$EXPECTED_DB' THEN RAISE EXCEPTION '접속 DB(%)가 이전 대상($EXPECTED_DB)과 다릅니다. 중단합니다', current_database(); END IF;
    END \$\$;
    TRUNCATE payment.payment, payment.webhook_event, payment.pg_transaction_snapshot, payment.reconciliation_mismatch"
  ```

  `-c`의 여러 문장은 한 트랜잭션으로 실행되므로 이름이 다르면 `TRUNCATE`는 실행되지 않는다.

  시퀀스는 다음 실행이 `RESTART`로 다시 맞춘다. 원인을 조사한 뒤 다시 실행한다. payment 앱이 새 결제를 받기 시작한 뒤에는 이렇게 비울 수 없다.

**전환 후 검증** (기동된 스택에서 읽기·확인 위주로 한다)

- readiness: `curl -s localhost:18085/actuator/health/readiness`(payment), `localhost:18080/actuator/health/readiness`(core), `localhost:18000/actuator/health`(gateway).
- 접속 DB 일치: payment 앱이 이전한 DB를 보고 있는지는 결제 조회가 이전 결제를 돌려주는지로 확인한다(아래). 조회가 비어 있으면 `TICKETING_PAYMENT_POSTGRES_DB`가 `$TARGET_DB`와 같은 값으로 기동됐는지 먼저 본다.
- 결제 조회: Gateway 경유로 이전된 예매의 결제를 읽는다. 테스트 사용자로 로그인해 받은 access 토큰으로 `GET http://localhost:18000/api/v1/payments/me?bookingIds=<이전된 예매 ID>`가 이전 결제 상태를 돌려주는지 본다.
- 웹훅 차단: `curl -i -X POST http://localhost:18000/api/v1/payments/webhooks/fake-pg`가 404다(Gateway가 라우팅 전에 막는다. 웹훅은 fake-pg가 payment 앱을 직접 호출한다).
- Kafka: `ticketing-payment` 그룹이 payment 앱으로 붙고 lag이 0으로 유지되는지, 위의 `--describe`로 본다. 결제·환불 `.dlt` 끝 오프셋이 늘지 않는지도 본다.
- 이벤트: core DB와 `$TARGET_DB`의 미완료 `event_publication`이 0건으로 유지된다.
- 대사(선택): `payment-batch`로 `paymentReconciliationJob`을 실행해 `reconciliation_mismatch` 증가분이 예상과 맞는지 본다. 이 Job은 결과 행을 기록한다(자동 보정은 하지 않는다).
- `./scripts/e2e/run.sh`는 이 단계의 검증 수단이 아니다. E2E 러너는 18000·18080·18081·18085·18090 등의 포트가 비어 있어야 시작하고 앱을 직접 띄우며, `.env`의 DB에 테스트 결제·환불을 남긴다. 전체 E2E는 포트가 비어 있고 DB가 격리된(운영 데이터가 없는 새 볼륨) 별도 환경에서만 돌린다.

**로컬 fake-pg의 한계** (실제 PG는 외부 시스템이라 해당하지 않는다)

- fake-pg는 거래·멱등 키 상태를 메모리(`TransactionStore`)에만 두고 웹훅 발송·재시도도 메모리 스케줄러에 예약한다. 재기동하면 이전 거래가 모두 사라져 이전한 결제의 환불·조회·대사 요청이 PG에서 거래를 찾지 못해 실패한다. 그래서 점검 창 중에 fake-pg를 끄지 않는다. 앱 구성을 되돌리는 롤백으로도 사라진 PG 거래와 예약은 복원되지 않는다.
- 웹훅 목적지는 `FAKE_PG_WEBHOOK_URL`(`fake-pg.webhook.url`)이며 fake-pg가 기동 때 설정으로 읽고 실행 중 바꾸는 수단이 없다. 이 저장소의 기본값은 payment 앱(18085)이므로 이미 이 기본값으로 떠 있는 fake-pg는 재기동하지 않아도 된다. 분리 전 기본값(core 18080)이나 다른 값으로 떠 있다면 목적지를 바꾸려면 재기동해야 하고, 그러면 위와 같이 이전 거래가 사라진다. 이 경우 이전 결제에 대한 환불·대사는 로컬에서 검증할 수 없는 것으로 받아들이거나, 데이터를 이전하지 않고 새 볼륨으로 시작한다.

**롤백**은 8단계(유입 재개) 전에 core DB의 `payment` 스키마가 있는 상태에서 대상 DB(`$TARGET_DB`)를 위 복구 절차로 비워 이전 구성으로 돌리는 절차다. 유입 재개 뒤에는 대상 DB가 새 거래의 원본이다. V18 적용 후에는 옛 스키마의 백업을 별도 DB에 복원해 이전 원본을 조회할 수 있다. fake-pg를 재기동했다면 위 한계 때문에 이전 거래는 복원되지 않는다.

## 10. 정산 DB 이전 (core DB settlement 스키마 → settlement_db)

같은 PostgreSQL 인스턴스의 전용 계정과 DB로 `booking_sale`, `booking_refund`, `daily_settlement`을 옮긴다. 기존 실행 이력은 core의 `batch` 스키마에 보관한다. 신규 실행 이력은 정산 DB에 쌓인다. [ADR 0051](./adr/0051-settlement-database-cutover.md)

### 준비

1. core가 확정·취소 이벤트를 Kafka로도 발행하고 기존 정산 리스너를 유지하는 전환 준비 버전을 배포한다. 기존 core, core-batch, Gateway의 실행 파일과 설정을 복귀용으로 보관한다. core와 정산 DB의 백업과 복원 경로를 확보한다.
2. `.env`에 `TICKETING_SETTLEMENT_POSTGRES_DB`, `TICKETING_SETTLEMENT_POSTGRES_USER`, `TICKETING_SETTLEMENT_POSTGRES_PASSWORD`를 설정한다. 개발 Compose의 DB 준비 스크립트는 core DB와 정산 DB의 `PUBLIC CONNECT`를 회수한다. 코어 DB 소유자 이외에 접속해야 하는 로컬 역할은 명시적으로 권한을 부여한다. Compose의 로컬 core 계정은 슈퍼유저로 ACL 검사를 우회한다.

   ```bash
   ./scripts/db/ensure-settlement-db.sh
   ./gradlew :settlement-batch:bootJar
   java -jar settlement-batch/build/libs/settlement-batch.jar --spring.batch.job.enabled=false
   ```

   이 명령은 Job과 Kafka 소비 없이 Flyway만 적용하고 종료한다. 대상 DB에서 성공한 Flyway 마이그레이션 V1~V3과 빈 정산 테이블을 확인한다.
3. `./scripts/db/test-migrate-settlement-data.sh`로 격리 환경에서 이전을 리허설한다. 서로 다른 원본·대상 DB, 기존 실행 이력, 할인·환불 데이터, 실패 롤백, 재실행, Kafka 중복 재생 및 이전된 조회를 검증한다.

### 점검 창

1. Gateway와 web에서 신규 쓰기 유입을 차단하고 예매 상태를 바꾸는 실행의 새 스케줄을 정지한다. 새 정산 앱의 Kafka 소비와 정산 배치도 정지 상태를 유지한다.
2. 기존 core·payment로 진행 중인 결제와 환불, 지연된 PG 웹훅, 예매 확정·취소의 로컬 적재와 Kafka 외부화를 배출한다. `ticketing-booking`과 `ticketing-payment`의 lag, 두 앱의 `event_publication` 미완료 행, 관련 DLT 및 웹훅을 [결제 이전의 배출 절차](#9-결제-db-이전-core-db-payment-스키마--payment_db)와 같은 방식으로 확인한다. DLT의 처리 결과와 파티션별 끝 오프셋을 기록한다. core의 `dailySettlementJob`에서 진행 중인 실행이 없어야 한다. 전환 전에 이어서 처리할 실패 실행은 기존 core-batch에서 같은 파라미터로 종료한다.
3. 기존 core와 core-batch를 중지한다. 쓰기가 정지된 상태를 유지하고 다음 명령을 실행한다. 정규화한 PK 순 스냅샷의 세 테이블 건수와 SHA-256을 기록한다. 복사나 대조가 실패하면 대상 트랜잭션이 롤백된다. 이미 채워진 대상은 전체 내용이 일치할 때 재사용한다.

   ```bash
   ./scripts/db/migrate-settlement-data.sh
   ```

4. 쓰기가 정지된 상태에서 두 소스 토픽의 파티션별 끝 오프셋을 기록한다. 이 값을 전환 기준점으로 사용한다. 아래는 기본 토픽 접두어와 로컬 Kafka 주소의 예다.

   ```bash
   for event in booking-confirmed booking-cancelled; do
     docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-get-offsets.sh \
       --bootstrap-server localhost:19092 --topic-partitions "ticketing.booking.$event"
     docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-get-offsets.sh \
       --bootstrap-server localhost:19092 --topic-partitions "ticketing.booking.$event.dlt"
   done
   ```

5. 새 정산 앱을 시작해 `ticketing-settlement` 그룹을 붙인다. 아래 명령으로 두 토픽의 각 파티션이 기준점까지 커밋됐는지 확인한다. 커밋 값이 `-`이면 해당 파티션의 시작·종료 오프셋이 같은 경우만 허용한다(`kafka-get-offsets.sh --time -2`로 시작 오프셋을 조회한다). 정산 DLT에 미처리 메시지가 있거나 외부화 미완료 행이 발견되면 전환을 보류하고 [DLT·미완료 발행 절차](#37-미완료-이벤트-발행이-쌓인다)를 따른다.

   ```bash
   docker run --rm --network host apache/kafka:4.3.2 /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:19092 --describe --group ticketing-settlement
   ./scripts/db/migrate-settlement-data.sh --verify-only
   ```

   재생 후 건수와 체크섬이 이전 결과와 일치해야 한다. 4단계 명령으로 두 DLT의 끝 오프셋을 다시 읽고, 기준점 이후에 늘어난 레코드와 이전에 처리 결과를 남기지 않은 레코드를 확인한다. lag이 0이어도 미처리 DLT와 미완료 발행이 있으면 6단계로 넘어가지 않는다.
6. 새 core와 Gateway를 시작하고 정산 배치 실행 대상 JAR를 `settlement-batch.jar`로 변경한다. Gateway의 `GET /api/v1/settlements?from=…&to=…`가 주최자 JWT로 해당 주최자의 과거 정산을 반환하는지 확인한다. 확인 후 쓰기와 배치 스케줄을 재개한다.

### 복귀와 지연 이벤트

- 쓰기 재개 전에는 새 정산 앱과 배치를 정지하고, 보관한 core 및 core-batch와 이전 Gateway 라우트를 복구한다. core DB의 세 테이블과 배치 이력으로 기존 조회와 쓰기를 다시 연다. 새 전환 시도는 이전 도구에서 원본과 대조를 통과하는 대상에서만 이어간다. 앞선 시도 후 core에 쓰기가 재개되어 대상과 달라졌다면 대상 백업을 확보하고 별도의 새 대상 DB를 준비한다.
- 쓰기 재개 후에는 `settlement_db`가 기준이다. 해당 DB를 보존하거나 백업에서 복원하고 Kafka 수신 상태를 대조한다. 옛 core 사본으로 돌아가면 이후 이벤트와 집계가 빠진다.
- 매일 정산 배치 전에 예매 확정·취소의 미완료 발행, 두 소스 토픽의 그룹 lag, 미처리 DLT를 점검한다. 늦게 도착한 이벤트가 영향을 준 KST 확정일·환불일을 식별하고, 소비가 끝나면 날짜별로 새 `run.id`를 붙여 `dailySettlementJob`을 재계산한다. 실패한 실행의 재시작에는 기존 파라미터를 사용한다. [배치 실행과 파라미터](./architecture/batch.md)

## 11. 관리자 기능 운영

- **회원 이용 정지**. 관리자 메뉴의 회원 목록에서 이메일로 대상을 찾고 사유를 입력한다. 정지 즉시 로그인과 토큰 갱신을 거부하고 refresh 토큰을 모두 폐기한다. 이미 발급된 access 토큰은 남은 유효 기간(기본 설정 기준 발급 후 최대 15분) 동안 사용할 수 있다. 해제한 회원은 다시 로그인한다. 정지된 회원을 관리자 역할로 바꿔도 이용 정지는 별도로 해제해야 한다. 마지막 활성 관리자의 강등은 거부된다.
- **공연 판매 중지**. 공연 목록에서 제목·주최자 ID로 대상을 찾고 사유를 입력한다. 공개 공연·회차·좌석 조회에서 제외되며, booking이 상태 변경 이벤트를 반영하면 신규 예매가 409 `PERFORMANCE_SUSPENDED`로 거부된다. 이미 만든 예매는 기존 기한과 상태 규칙에 따라 결제·만료·조회·취소할 수 있다. 주최자 전용 상세와 판매 현황·정산 조회도 가능하다.
- **판매 상태 반영 확인**. 관리 화면에서 중지·재개에 성공했는데 신규 예매 동작이 계속 다르면, core DB의 미완료 발행에서 `PerformanceSuspensionChanged`의 booking 수신 건을 확인한다. 같은 이벤트를 다시 처리해도 최신 판매 상태가 유지된다. 공개 목록을 이미 연 브라우저에서는 새로고침해 확인한다. [이벤트 발행 진단](#37-미완료-이벤트-발행이-쌓인다)
- **예매 강제 취소**. 결제 대기·확정 예매의 상세에서 사유를 입력하고 강제 취소를 확정한다. 확정 예매는 취소 후 비동기로 환불한다. 예매 상세에서 취소 유형을, 감사 로그의 `BOOKING_FORCE_CANCELLED`에서 처리자와 입력한 사유를 확인한다.
- **공연 취소**. 공연 관리에서 판매 중 또는 판매 중지 상태의 공연을 취소한다. 공개 조회에서 즉시 제외되고 취소는 되돌릴 수 없다. 예매 취소와 환불은 순차 처리된다. 감사 로그의 `PERFORMANCE_CANCELLED`에서 처리자와 사유를 확인한다.
- **감사와 예매 조사**. 감사 로그에서 `MEMBER_SUSPENSION_CHANGED` 또는 `PERFORMANCE_SUSPENSION_CHANGED`를 찾아 처리자, 상태 전이, 정지·중지 사유를 확인한다. 예매 관리 화면에서는 예매·좌석·쿠폰 정보와 결제 상태를 함께 볼 수 있다. 결제 조회 실패 문구가 표시되면 예매 ID로 payment 상태를 다시 확인한다. 결제 내역이 없을 때는 별도 문구가 표시된다.

### 공연 취소 후 남은 예매 확인

1. 취소된 공연 행에서 **예매 현황**을 연다(`/admin/bookings?performanceId=…`). 관리자 API에서는 `GET /api/v1/admin/bookings?performanceId=…`를 사용한다. `PENDING`과 `CONFIRMED`를 각각 필터링해 남은 예매를 확인한다. 두 상태가 모두 비면 예매 취소가 끝난 것이다. 결제 환불 완료는 예매 상세의 결제 상태로 확인한다.
2. 남은 예매가 줄지 않거나 오류가 반복되면 core DB의 미완료 발행을 확인한다. 아래 SQL의 예시 ID `123`을 대상 공연 ID로 바꾼다.

   ```sql
   select id, listener_id, status, completion_attempts, publication_date
   from public.event_publication
   where completion_date is null
     and event_type = 'io.github.ticketing.catalog.PerformanceCancelled'
     and listener_id like '%ScheduleRegisteredListener.on%'
     and serialized_event::jsonb ->> 'performanceId' = '123';
   ```

3. 리스너 오류 원인을 찾아 고친 뒤 [미완료 이벤트 재처리](#37-미완료-이벤트-발행이-쌓인다) 절차대로 core를 재기동한다. 이미 커밋된 청크는 유지되고 남은 예매를 이어서 취소한다. 같은 발행 ID의 `completion_date`와 두 상태의 예매 목록을 다시 확인한다.
