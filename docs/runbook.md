# 런북

로컬·개발 환경에서 스택을 운영하고 문제를 진단하는 절차다. 처음 환경을 구성하는 방법은 [onboarding.md](./onboarding.md)를 본다. 아직 배포 환경(k8s)이 없으므로 M6 이후 이 문서를 확장한다.

## 1. 구성 요소와 의존

| 구성 | 포트 | 의존 | 상태 확인 |
|---|---|---|---|
| gateway | 18000 | Redis, core, realtime | `/actuator/health` |
| core | 18080 | PostgreSQL, Redis, fake-pg | `/actuator/health` |
| realtime | 18081 | Redis | `/actuator/health` |
| web | 13000 | Gateway, Redis(BFF 세션) | 브라우저 |
| fake-pg | 18090 | core(웹훅) | `docker compose ps` |
| PostgreSQL | 15432 | - | `pg_isready` |
| Redis | 16379 | - | `redis-cli ping` |

기동 순서는 PostgreSQL·Redis·fake-pg → core·realtime → gateway → web이다. 종료는 역순이다.

## 2. 기동·종료·초기화

```bash
docker compose up -d --wait         # 인프라(PostgreSQL, Redis) 기동
./scripts/dev/run.sh --fake-pg      # fake-pg는 호스트 jar로 기동(로그: build/dev/logs/fake-pg.log)
docker compose ps                   # 상태
docker compose down                 # 종료 (데이터 유지)
docker compose down -v              # 종료 + PostgreSQL 볼륨 삭제 (데이터 전부 사라짐)
```

- `down -v`는 되돌릴 수 없다. 로컬 데이터를 버려도 될 때만 쓴다. Flyway 마이그레이션은 core 기동 때 다시 적용된다.
- Redis는 볼륨이 없어서 컨테이너를 다시 만들면 대기열·선점·세션이 사라진다.
- Compose 기본 네트워크의 브릿지 이름은 `br-ticketing`으로 고정한다(서브넷은 Docker가 배정). 방화벽 규칙을 서브넷이 아닌 이 인터페이스에 걸어서 네트워크를 재생성해도, 다른 프로젝트와 서브넷이 겹쳐도 유지된다. 다른 프로젝트도 고유한 브릿지 이름을 쓰고 인터페이스 기준으로 허용하면 같은 문제를 피한다.
- Linux에서 UFW를 쓰면 최초 1회 `sudo ./scripts/setup-firewall.sh`를 실행한다(멱등). 기존 네트워크에는 이름이 적용되지 않으므로 `docker compose down` 후 다시 기동한다(Redis 데이터는 사라진다).
- 포트가 이미 사용 중이면 `ss -ltnp 'sport = :18080'`으로 점유 프로세스를 찾는다.

## 3. 장애 진단

### 3.1 앱이 뜨지 않는다

1. `docker compose ps`에서 postgres, redis가 `healthy`인지 본다.
2. `.env`가 있고 `POSTGRES_*`, `TICKETING_JWT_SECRET`, `FAKE_PG_WEBHOOK_SECRET`이 채워져 있는지 본다. `.env.example`과 비교한다.
3. 빌드가 `:core:generateJooq`에서 실패하면 Docker가 떠 있는지 확인한다. 코드 생성이 Docker를 쓴다.
4. E2E로 띄웠다면 `build/e2e/logs/`의 앱별 로그를 본다.

### 3.2 결제가 PENDING에서 넘어가지 않는다

결제는 이벤트 기반이다. core가 fake-pg에 요청하고 fake-pg가 웹훅으로 결과를 돌려준다.

1. `build/dev/logs/fake-pg.log`(E2E는 `build/e2e/logs/fake-pg.log`)에서 요청이 도착했고 웹훅을 보냈는지 본다.
2. 웹훅이 닿지 않으면 core가 18080에서 호스트로 떠 있는지, `FAKE_PG_WEBHOOK_URL`(기본 `http://localhost:18080/api/v1/payments/webhooks/fake-pg`)이 맞는지 본다.
3. 웹훅이 거절되면 core와 fake-pg의 `FAKE_PG_WEBHOOK_SECRET`이 같은지 본다. 서명 검증이 이 값을 쓴다.
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

## 4. Batch 실행

Job은 `core-batch`에서 하나씩 실행한다. 규칙과 파라미터의 기준은 [batch.md](./architecture/batch.md)다.

| Job | 파라미터 | 언제 |
|---|---|---|
| `dailySettlementJob` | `targetDate`, `run.id` | 일별 정산 |
| `expirePendingBookingsJob` | `run.id` | 미결제 예매 만료 처리 |
| `reconcileSeatSnapshotJob` | `run.id` | 좌석 스냅샷 보정 |
| `releaseStaleCouponReservationsJob` | `run.id` | 기한 지난 RESERVED 쿠폰 복원 |
| `paymentReconciliationJob` | `targetDate`, `run.id` | 결제 대사 |

```bash
./gradlew :core-batch:bootRun --args='--spring.batch.job.name=dailySettlementJob targetDate=2026-11-10,java.time.LocalDate run.id=1,java.lang.Long'
```

- 완료된 같은 날짜를 다시 계산하려면 `run.id`를 새 값으로 바꾼다. 결과는 upsert 또는 날짜 단위 재적재라서 같다.
- 실패한 실행은 같은 `targetDate`와 `run.id`로 재시작한다. 새 `run.id`를 쓰면 새 실행이 된다.
- 정산일은 Asia/Seoul 기준이다. 지난 날짜의 정산은 다시 계산하지 않는다.
- Batch 메타데이터는 PostgreSQL `batch` 스키마에 있다. 실행 이력은 여기서 조회한다.

```bash
docker compose exec postgres psql -U "$TICKETING_POSTGRES_USER" -d "$TICKETING_POSTGRES_DB" \
  -c 'select job_instance_id, job_name from batch.batch_job_instance order by 1 desc limit 10;'
```

## 5. 데이터 확인

- 스키마는 `member`, `catalog`, `booking`, `payment`, `settlement`, `batch`다. 스키마 사이의 조인은 코드에서 만들지 않는다. 디버깅용 조회도 한 스키마씩 한다.
- 구조는 [erd.md](./architecture/erd.md)를 본다.
- 스키마 변경은 Flyway 마이그레이션으로만 한다. 적용된 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.

## 6. 테스트·검증 실패 대응

- 실패는 실패한 그대로 보고한다. 테스트를 지우거나 `@Disabled`로 우회하지 않는다.
- 로그는 전체를 출력하지 않고 `rg -n -m 20 '오류 패턴' 로그경로`로 위치를 좁힌다.
- Gradle 테스트 결과 파일이 깨졌다면 test와 check를 동시에 돌렸는지 확인하고 순차로 다시 실행한다.
- E2E 실패 시 `build/e2e/logs/`의 앱별 로그를 보고, `--keep`으로 스택을 남겨 재현한다.
- 포트 점유로 E2E가 시작되지 않으면 18000, 18080, 18081, 18082, 13000(`--web`)을 비운다.
- Testcontainers 컨테이너가 남아 있으면 `docker ps`로 확인하고 Ryuk이 정리하지 못한 것만 지운다.

## 7. 변경 시 확인 사항

| 바꾼 것 | 확인 |
|---|---|
| core 모듈 간 의존 | Modulith 구조 검증, `./gradlew :core:modulithDocs` |
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
| Grafana | http://localhost:13001 | 대시보드(`ticketing-overview`). 익명 Admin으로 열린다 |
| Prometheus | http://localhost:19090 | 지표 조회. k6 지표도 remote-write로 들어온다 |
| Tempo | http://localhost:13200 | trace 조회. 보통은 Grafana Explore에서 본다 |

- Grafana는 13000이 아니라 13001을 쓴다(13000은 web).
- 앱은 span을 기본으로 내보내지 않는다. 켜려면 앱을 띄울 때 `TRACING_EXPORT_ENABLED=true`를 주고, 비율은 `TRACING_SAMPLING_PROBABILITY`(기본 1.0)로 정한다. 내보내는 주소는 `OTLP_TRACES_ENDPOINT`(기본 `http://localhost:14318/v1/traces`)다. `run.sh --observability`는 이 값을 `true`와 `0.1`로 넣는다.
- 지표는 모든 Spring 앱의 `/actuator/prometheus`에서 Prometheus가 수집한다. 앱별 포트는 `application.yml`을 본다.

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
