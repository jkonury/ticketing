# 내부 예열의 모니터링 지표 분리 계획

- 작성일: 2026-11-16
- 상태: 설계 문서 작성 완료, 구현 미착수. 이번 작업은 문서만 작성한다.
- 결정: [ADR 0033](../adr/0033-internal-warmup-metrics-isolation.md)
- 관련: [내부 예열 트래픽 구성](../adr/0032-warmup-traffic-mix.md), [로드맵](../roadmap.md)

## 목표와 현재 문제

내부 예열의 요청 수·지연·오류·캐시 지표를 일반 요청과 분리한다. 기본 Grafana 대시보드와 부하 테스트 집계에는 일반 요청만 반영하고, 예열 지표는 따로 조회할 수 있게 한다.

현재 예열은 실제 Gateway 라우트·예매 핸들러·서비스·캐시를 호출한다. DB 롤백과 Redis 키 격리는 업무 데이터를 보호하지만 HTTP 타이머, Gateway 단계 타이머, 예매 단계 타이머, 좌석 캐시 hit/miss의 누적값은 되돌리지 않는다. payment 예열의 PG 거래 조회도 HTTP 관측 경로를 타며 fake-pg 수신 지표에 포함된다. 예열 종료 후에도 누적 카운터와 히스토그램에는 영향이 남는다.

서버 지표 차분 스크립트는 지정한 라벨 외의 라벨을 합산한다. 태그만 추가하면 집계 시 다시 섞일 수 있으므로 수집과 조회·집계를 함께 변경한다.

## 지표 계약과 적용 범위

요청에 귀속 가능한 대상 지표에는 두 값만 갖는 `traffic` 태그를 붙인다. `application`은 내부 예열 이외 요청이며 외부 k6·Gatling 예열도 포함한다. `warmup`은 신뢰할 수 있는 내부 예열 실행에서 발생한 요청이다. 외부 부하 테스트의 계열·phase 구분은 기존 계약을 유지한다.

| 대상 | 처리 |
|---|---|
| 완료된 HTTP 서버·클라이언트 관측 지표 | `traffic=application|warmup`으로 분리 |
| Gateway 라우트 요청·단계 타이머 | 같은 태그로 분리 |
| core 예매 생성 단계 타이머·좌석 캐시 hit/miss | 같은 태그로 분리 |
| realtime HTTP 지표 | 기본값 `application`을 붙여 공통 대시보드 조회에 포함 |
| JVM·GC·Hikari·Netty 풀·PostgreSQL·Redis 지표 | 공유 자원 전체의 원본 유지 |
| 진행 중 HTTP 요청 지표 | 전체 요청의 원본 유지 |

신규 대상 요청 지표는 예열 비활성 상태에서도 반드시 `traffic` 값을 갖는다. 요청 ID·실행 ID·자격·서명은 지표 태그에 넣지 않는다. 자동 계측이 없는 HTTP 클라이언트를 새로 계측하는 것은 이 작업의 목표가 아니며, 이미 생성되는 완료 지표를 분류한다.

공유 자원 사용량은 실제 비용이므로 예열 값을 빼거나 초기화하지 않는다. 진행 중 요청을 나타내는 LongTaskTimer는 관측 시작 시 태그가 결정되고, 이후 인증 결과를 추가해도 소급 변경되지 않으므로 완료 지표와 분리한다. 근거는 [Micrometer Observation Components](https://docs.micrometer.io/micrometer/reference/observation/components.html)다.

## 구현할 변경

### 요청 분류와 실행 범위

- Gateway는 기존 일회성 자격 검증으로 설정한 exchange 속성, core는 기존 서버 역할 JWT·예열 자격 검증 결과를 사용한다. 헤더 존재, 음수 ID, 기동 시각만으로 분류하지 않는다. 위조·만료 자격 요청은 `application`으로 기록한다.
- 완료된 서버 HTTP 지표는 검증 결과를 요청 속성에 보관하고 Observation 확장 지점에서 태그에 반영한다. SecurityContext가 정리된 뒤에도 최종 분류를 읽을 수 있어야 한다. 자격 검증 후의 업무 오류도 같은 예열 요청으로 집계한다.
- Gateway 라우트 지표는 `GatewayTagsProvider`로 확장한다. 단계 타이머는 exchange에서 얻은 분류를 명시적으로 전달하고, 필요한 비동기 전파에는 Reactor 컨텍스트를 사용한다. 요청 처리에 ThreadLocal을 사용하지 않는다.
- core 직접 예열은 booking 모듈 내부 실행 범위에서 표시한다. payment는 동기 예열 이벤트 수신 시 자체 범위를 열고 PG 송신 관측까지 전달한다. 동기 실행 범위는 이전 값을 저장하고 `finally`에서 복원해 중첩·예외에도 누출되지 않게 한다. 향후 비동기로 바꾸면 명시적 컨텍스트 전달이 필요하다.
- 모듈별 실행 범위를 해당 모듈 내부에 둔다. 공용 유틸 모듈이나 다른 코어 모듈의 내부 클래스 참조는 추가하지 않는다.
- 미리 등록하는 예매 단계 타이머·캐시 카운터는 태그값별로 등록하고 기록 시 선택한다. 요청마다 `MeterFilter`를 바꾸거나 예열 종료 후 registry의 값을 초기화하지 않는다. MeterFilter는 meter 등록 단위이므로 요청별 분류 수단으로 사용하지 않는다([공식 문서](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html)).
- 내부 자격 발급 요청은 서버 역할 JWT 검증 후 정상 발급된 경우만 `warmup`으로 표시한다. readiness 폴링과 일반 health 요청은 `application`으로 남기고 기존 관리 경로 조회 정책을 따른다.

### fake-pg로 전달하는 예열 표식

payment 예열의 PG 거래 조회에만 다음 헤더를 추가한다. 기존 PG 호출 경로와 공유 웹훅 비밀을 사용하며 새 비밀 설정은 추가하지 않는다.

| 헤더 | 값 |
|---|---|
| `X-Startup-Warmup-Timestamp` | 주입된 `Clock`의 현재 Unix 시각(초), 10진수 문자열 |
| `X-Startup-Warmup-Signature` | 아래 입력의 HMAC-SHA256 결과, 소문자 hex |

서명 입력은 다음 다섯 줄을 LF로 연결한 UTF-8 문자열이며 마지막 개행은 없다.

```text
startup-warmup
{timestamp}
GET
/v1/transactions
date=1970-01-01
```

fake-pg는 메서드·경로·날짜가 위 계약과 같고, 날짜 쿼리 값이 하나이며, 현재 시각과의 차이가 ±30초 이내이고, 서명이 일치할 때만 `warmup`으로 표시한다. 서명은 디코딩한 고정 길이 바이트를 상수 시간 비교한다. 누락·파싱 실패·만료·서명 오류는 업무 응답을 바꾸지 않고 `application`으로 집계한다. 표식은 관측 분류에만 쓰며 인가 우회를 허용하지 않는다.

이는 짧은 유효기간의 관측 표식으로, 기간 내 재사용 자체를 막는 프로토콜은 아니다. 비밀과 표식은 로그에 남기지 않으며 core↔Gateway의 기존 예열 자격을 PG로 전달하지 않는다. 이 계약은 fake-pg용이고 실제 PG 도입 시 재검토한다.

### 예열 구간과 자원 지표

- core·Gateway에 `ticketing.startup.warmup.active` 게이지(0 또는 1)와 `ticketing.startup.warmup.finished.at` 게이지(Unix 초)를 추가한다. 시작 전·비활성 상태의 값은 모두 0이다.
- 각 앱의 내부 예열 runner 진입부터 종료까지 active를 1로 유지하고, 완료·실패·시간 초과·중단 시 `finally`에서 0과 종료 시각을 기록한다. core가 이후 처리하는 Gateway발 예열 요청은 Gateway runner 구간에 해당한다.
- Grafana에서 앱별 예열 구간을 공유 자원 그래프와 함께 표시한다. 종료 시각은 마지막 예열 종료를 뜻하며 성공 여부를 뜻하지 않는다. 결과 상태는 기존 완료 로그로 확인한다.

### 대시보드·부하 테스트 집계

- Overview·Gatling 대시보드에 `traffic` 선택기를 추가한다. 기본은 `application`, 선택지는 `application`·`warmup`·전체다. 완료 요청 수·지연·오류율의 분자와 분모·단계 타이머·캐시에 같은 선택을 적용한다. 공유 자원·진행 중 요청 패널은 전체 값임을 표시하고 이 필터를 적용하지 않는다.
- PromQL에서 `application` 선택은 해당 값 또는 라벨 없는 과거 시계열, `warmup`은 해당 값만, 전체는 필터 없이 조회한다. 과거 데이터에 이미 섞인 예열은 복원할 수 없다고 표시한다.
- `scripts/loadtest/metrics-delta.py`에 `--traffic application|warmup|all`을 추가한다. 기본값은 `application`이다. 원본 스냅샷은 보존하고 전체 시계열의 reset·소실 검증 후, 요청·업무 지표의 요약·통제 입력 검증·counter 집계 전에 선택을 적용한다. 공유 자원 차분은 그대로 유지한다.
- 예열 전용·전체 선택에서는 일반 부하 입력과의 건수 비교를 수행하지 않고 적용 대상 아님을 결과에 명시한다. 일반 선택의 기존 통제 입력 검증은 유지한다.
- 차분 결과에 선택값을 기록하고 `scripts/loadtest/dashboard-metrics.py`와 exporter에도 전달한다. exporter의 파생 서버 지표에 `traffic=application|warmup|all`을 붙여 서로 합산되지 않게 하고 Gatling 대시보드의 해당 패널에도 선택을 적용한다. 기존 계열·phase 선택을 유지하며 `all` 결과와 개별 결과를 함께 합산하지 않는다.
- `scripts/loadtest/prom-snapshot.sh`도 같은 선택 옵션과 기본값을 사용한다. 내부 예열 이후의 측정뿐 아니라 예열과 측정이 겹친 경우에도 요청 지표가 분리되어야 한다.
- 라벨 없는 과거 스냅샷·차분 결과·exporter 출력은 호환 조회에서 `application`으로 취급한다. 과거 원본과 성능 보고서를 소급 수정하지 않는다.

## 검증과 완료 기준

- [ ] 실제 Prometheus 노출 결과에서 대상 count·sum·bucket·캐시 counter에 동일한 traffic 태그가 붙는다.
- [ ] 예열만 실행한 경우 대상 요청·업무 지표의 application 값은 증가하지 않고 warmup 값만 증가한다. health·scrape와 같은 관리 요청은 별도로 구분해 단언한다.
- [ ] 일반 요청과 예열을 동시 실행해도 분류가 섞이지 않는다. 동기 범위 중첩·예외 및 reactive 오류·취소·재시도 이후에도 일반 요청은 application으로 기록된다.
- [ ] 위조·만료 자격과 PG 표식의 누락·형식 오류·잘못된 경로·서명 오류는 application에 남는다. 고정 Clock으로 ±30초 경계와 미래 시각도 검증한다.
- [ ] 예열 비활성 시 일반 요청의 동작·application 분류가 유지된다. DB 롤백·Redis 격리·일반 SSE 경로에 대한 기존 검증도 통과한다.
- [ ] 게이지는 비활성·정상 종료·실패·시간 초과·중단에서 정한 값을 보인다. 공유 자원 지표의 원본은 유지된다.
- [ ] Python 회귀 테스트로 혼합 데이터의 기본 제외, 예열 전용·전체 선택, 과거 데이터 호환, 통제 입력 판정, 기존 reset·시계열 소실 검출과 exporter 전달을 확인한다.
- [ ] 전체 테스트와 check를 저장소 전용 스크립트로 순차 실행하고 `ktlintCheck detekt`, 예열 활성 E2E, 문서 검사를 통과한다.

실제 핸들러·보안 경계·호출량·readiness 정책은 유지한다. Tempo·Scouter의 분리 정책, 신규 관측 스택, 성능 목표 재측정은 범위 밖이다. 구현 완료 전까지 로드맵의 이 작업 항목과 위 체크박스는 미완료로 둔다.
