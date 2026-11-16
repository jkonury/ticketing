# 내부 예열 ↔ 외부 예열 격차: 추가 실험 대상과 접근 가이드

- 작성일: 2026-11-16
- 상태: 계획만 작성했다. 실험 실행·러너·서비스 코드 변경은 하지 않았다.
- 배경: [문서 18](../performance/m5/18-warmup-traffic-mix.md)에서 core 탈최적화가 203건에서 52건으로 줄었지만 내부 예열 p95는 750~772ms로, 외부 15회 예열([문서 19](../performance/m5/19-warmup-tcp-diagnosis.md)의 435·436ms)과 약 330ms 차이가 남는다. "core JIT가 주원인" 가설은 지지되지 않았다. 다음 실험은 격차가 어느 서비스·단계에서 생기는지 나눈 뒤 고른다.
- 상위 계획: [내부 예열 콜드 900ms 계획](./hot-seats-internal-warmup-cold-900ms.md)

## 1. 기존 산출물로 나눈 격차

내부는 `build/loadtest/runs/20261116-160339-hot-seats/{1,2,3}`, 외부는 `build/loadtest/runs/20261116-192232-hot-seats/{1,2}`다. 집계 방법은 `server-delta.json`의 `histograms`·`hikariDelta`와, Gatling 원시 `simulation.log`를 `scripts/loadtest/gatling-results.py`의 `LogReader`로 읽은 값이다(집계 스크립트는 저장하지 않았다). 외부는 2회뿐이고 회차당 표본이 1개라 범위로만 적는다.

| 지표 (예매 폭주 구간) | 내부 | 외부 |
|---|---|---|
| Gatling `booking_create` p50 / p95 (ms) | 575~667 / 750~772 | 253~284 / 435~436 |
| 예매 시작 분산(첫 시작~마지막 시작, ms) | 187~246 | 239~266 |
| Gateway `core-bookings-create` 라우트 avg / p95 (ms) | 217~422 / 381~619 | 96~129 / 208~249 |
| Gateway stage `admission-body` avg (ms) | 27~63 | 17~19 |
| Gateway stage `admission-redis` avg (ms) | 27~58 | 15~27 |
| Gateway stage `ratelimit-redis`(예매 생성) avg (ms) | 33~168 | 13~22 |
| Gateway stage `ratelimit-redis`(`core-api`, 결제) avg (ms) | 109~176 | 6 |
| Gateway stage `proxy`(예매 생성) avg / p95 (ms) | 107~201 / 240~492 | 52~65 / 146~176 |
| core `POST /api/v1/bookings` avg / p95 (ms) | 54~90 / 129~201 | 19~23 / 46~57 |
| core Hikari 획득 평균 (ms) | 13~27 | 2.1~3.6 |
| realtime `queue_enter` p50 (Gatling, ms) | 2,046~2,200 | 529~530 |

해석(가설이며 원인 확정이 아니다):

1. 꼬리만이 아니라 **분포 전체가 300ms 이상 밀렸다**(p50 차이도 비슷하다). 부하 모양(시작 분산)은 두 계열이 같다. 경로 몇 개가 느린 것보다 폭주 동안의 처리 능력이 전반적으로 낮은 모습이다.
2. core를 기다리지 않는 Gateway 단계(admission-body·admission-redis·ratelimit-redis)도 2~25배 느리다. 격차를 core만으로 설명할 수 없고 Gateway 이벤트 루프·Lettuce·rate limiter 상태가 후보다. 예열은 `GatewayAttributes.STARTUP_WARMUP`으로 rate limiter와 그 안의 `proxy` 타이머 경로를 건너뛴다(`gateway/src/main/kotlin/io/github/ticketing/gateway/ratelimit/RateLimitFilter.kt`). 예열 회원 키도 -1 하나다.
3. core 처리 시간은 평균 약 +35~65ms이고 Hikari 획득은 약 10배다. DB 연결 점유가 길어진 것인데 JIT 때문인지 PG 백엔드 상태 때문인지는 미분리다.
4. realtime은 내부 예열이 없어 완전 콜드다(대기열 진입 약 4배). 예매보다 약 55초 앞선 구간이라 예매 p95와 직접 겹치지는 않는다.
5. Gateway→core 풀은 `max-idle-time: 20s`(`gateway/src/main/resources/application.yml`)다. 내부 계열의 폭주 직전 연결 수는 측정한 적이 없다(문서 19에서 미완료). 외부 계열은 폭주 직전 Gateway→core 연결이 197~310개였다.

## 2. 추가 실험 (우선순위 순)

공통 조건은 기존과 같다: Gatling 2g·1,000명·관측 ON·Scouter OFF·CPU 0-3/4-15·JFR OFF·단독 실행·매회 새 기동. 계열은 섞지 않는다.

### E1. 서비스별 귀속: 외부 예열 후 한 서비스만 재기동 (가장 결정적)

- 외부 15회 예열 → {core | gateway | realtime} 중 하나만 재기동(그 서비스는 내부 예열 ON) → 본 측정. 변형마다 2~3회.
- 러너에 진단 전용 옵션(예: `--restart-after-warmup <svc>`)이 필요하다. `scripts/loadtest/run.sh`의 `--internal-warmup`과 `--warmup` 배타 검사는 이 옵션에서만 완화한다. 서비스 코드는 바꾸지 않는다.
- 판정: 재기동한 서비스의 p95가 435 → 760 쪽으로 가장 많이 움직이면 그 서비스가 격차의 주인이다. 합계가 330ms보다 크면 상호작용으로 기록한다.
- JIT뿐 아니라 연결 풀·Lettuce·PG 백엔드 같은 **비JIT 상태까지 함께 귀속**된다.

### E2. 컴파일 상태 차분: `jcmd <pid> Compiler.codelist`

- 본 부하 직전(readiness 이후, 측정 시작 전)에 core·gateway의 컴파일 메서드 목록과 등급을 남긴다. 두 계열 각 1회.
- 패키지·클래스 단위로 "외부는 C2(4)인데 내부는 없거나 C1인 메서드"를 집계한다.
- 판정: 차분이 Gateway 필터·Lettuce·rate limiter·reactor-netty 클라이언트, core Hibernate·Jackson 등 특정 군에 몰리면 그 경로를 예열 목록에 추가한다. 차분이 작으면 JIT 가설을 접고 E4로 간다.
- JFR보다 오버헤드가 없고 탈최적화 건수보다 "무엇이 빠졌는지"를 직접 보여 준다.

### E3. 외부 예열 용량-반응: `--warmup --warmup-rounds 1`, `3`

- 각 2회. 기존 러너로 바로 실행할 수 있다.
- 판정: 1회만 예열해도 약 450ms 근처면 격차는 **양이 아니라 경로·상태 차이**다(→ E1·E2로 빠진 경로를 찾는다). 회차에 따라 점진적으로 줄면 양·시간도 원인이다(→ 예열 회차·혼합 비율 조정 대상).

### E4. 내부 계열 TCP·풀 계측 마무리 (문서 19 잔여)

- `--internal-warmup` 3회를 문서 19와 같은 TCP 계측으로 실행해 폭주 직전 Gateway→core 연결 수, Recv-Q, Hikari 획득을 외부와 비교한다.
- 가능하면 Hikari 사용 시간(`hikaricp_connections_usage_seconds`)과 PG `pg_stat_statements` 평균 시간을 server-delta에 추가해, core의 연결 점유 증가가 앱 쪽인지 PG 쪽인지 나눈다.

권장 순서는 E2 → E1 → E3 → E4다. E2·E3은 앱 변경이 없어 비용이 가장 낮고, E1은 러너 변경이 필요하다.

## 3. 결과별 대응 방안

| 결론 | 방안 |
|---|---|
| Gateway가 주원인 | ① 예열의 rate limiter 우회를 없애고, 예열 전용 음수 회원 키를 여러 개(TTL) 써서 `RedisRateLimiter.isAllowed`·`proxy` 타이머 경로를 실제 실행한다(버킷 소진은 키 분산과 예열 전용 limiter 설정으로 피한다). ② admission-body·admission-redis를 실제 토큰 형식으로 회원별 실행한다. ③ 필요하면 예열 끝에 Gateway→core 풀을 폭주 규모(약 300)로 유지·재생성한다(`max-idle-time` 변경은 별도 합의). |
| core가 주원인 / JIT 차분 있음 | E2 차분에 나온 메서드군을 예열 혼합에 넣는다(예: PG POST 본문 쓰기 경로는 fake-pg 전용 예열 엔드포인트). [ADR 0032](../adr/0032-warmup-traffic-mix.md) 보강. |
| core 연결 점유·PG 쪽 | PG 백엔드 예열: Hikari 연결 모두에 실제 SQL을 pgjdbc server-prepare 임계 이상 실행하고, 측정 회차 테이블·인덱스를 읽는 조회로 버퍼를 데운다(쓰기 없이). |
| realtime | realtime 내부 예열(음수 회차 대기열 진입·SSE 티켓·구독을 격리 키로 실행). 예매 p95 효과는 작을 것으로 보고 대기열·SSE 지표 개선 목적으로 별도 판정한다. |
| 경로 차이 없음, 양·시간만 | 예열 회차·혼합 비율 조정, 또는 외부 hot-seats 흐름으로 학습한 AOT 캐시를 내부 예열과 함께 쓰는 계열을 검토한다([ADR 0026](../adr/0026-aot-cache-startup.md) 개정 필요). |

설계·서비스 동작을 바꾸는 방안(rate limiter 예열, realtime 예열, 풀 설정, AOT 조합)은 실험 결과를 보고 사용자와 합의한 뒤 ADR로 남긴다.

## 4. E2 결과 (2026-11-16, 계획의 E2 실행)

- 러너에 진단 전용 `--codelist`를 추가했다(`scripts/loadtest/run.sh`). Gatling 시작 직전(`pre`)과 52초 뒤(예매 폭주 직전, `burst`)에 core·gateway의 `jcmd Compiler.codelist`를 `codelist-<서비스>-<pre|burst>.txt`로 남긴다. 집계는 `scripts/loadtest/codelist-diff.py`(사용 가능한 nmethod만, 메서드별 최고 level)다.
- 실행: 내부 `20261116-220439-hot-seats/1`(p95 796ms)·반복 `20261116-222539-hot-seats/1`(810ms), 외부 15회 예열 `20261116-220644-hot-seats/1`(390ms). 모두 정합성 통과·300ms 미달이다. 각 1~2회뿐이다.

| 서비스·시점 | C2(level 4) 메서드 수: 외부 / 내부 A / 내부 B |
|---|---|
| core pre | 6,696 / 5,238 / 5,391 |
| core burst | 6,702 / 5,526 / 5,674 |
| gateway pre | 4,307 / 3,324 / 3,458 |
| gateway burst | 4,331 / 3,871 / 3,997 |

- 노이즈 하한: 내부 A·B끼리 "한쪽만 C2"인 메서드가 core 약 880~1,030개, gateway 약 620~750개이며 양방향이 비슷하다. JIT는 실행마다 달라진다.
- 외부 C2이고 내부 두 회차 모두 비C2인 메서드: core 2,289(두 회차 모두 미컴파일 590), gateway 1,046(256). 반대 방향(내부 두 회차 C2·외부 비C2)은 935·441이다. 즉 외부가 약 2.4배 많아 **차이는 노이즈보다 크고 한쪽으로 치우친다**. 다만 외부 C2 수가 core +25%, gateway +10~25% 수준이라 "내부는 JIT가 크게 비어 있다"까지는 아니다.
- 두 내부 회차 모두 미컴파일인 메서드의 군: core는 Hibernate SQM→SQL 변환(`BaseSqmToSqlAstConverter`, `AbstractSqlAstTranslator`, `SqmQueryImpl`, `SimpleNonSelectQueryPlan`)과 Lettuce 연결, gateway는 Lettuce·reactive Redis 스크립트(`data.redis.core.script`)·rate limiter 필터·reactor-netty 풀·헤더 필터다.
- 해석(가설): gateway 쪽은 예열이 rate limiter(`RedisRateLimiter` 스크립트)를 건너뛰는 것과 일치하고, 이 구간이 Gateway stage 지연이 가장 크게 벌어진 곳이다(1절). core 쪽은 예열이 실행하지 않는 HQL/JPQL 쿼리 경로가 후보지만 실제 호출 지점은 확인하지 않았다. 이 값은 "컴파일 여부"일 뿐 지연 기여율이 아니다.
- 한계: 외부 1회·내부 2회, `burst` 덤프는 시작 52초 뒤라 대기열 단계 트래픽 이후 상태다. 외부 계열은 예열에서 대기열·SSE·웹훅까지 전체 흐름을 실행하므로 prometheus·OTLP 내보내기 군의 차이는 요청 경로 밖일 수 있다.
- 다음: gateway rate limiter·Lettuce 예열(3절 첫 행)을 가설로 E1(서비스별 귀속)을 먼저 확인해 효과를 확인한 뒤 구현을 합의한다.
