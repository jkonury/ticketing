# M6 2-5 Kafka 외부화 후 회귀 측정

비교 기준은 [01 Gatling 기준선](01-pre-split-baseline-gatling.md)이다. 이번에는 payment 분리 전 core 안에서 결제 계약 이벤트가 Kafka를 거친다. 원본은 `build/loadtest/runs/20261118-232600-hot-seats/1`, `2`, `3`이다.

## 조건

2026-11-18 23:26~23:32 KST, 커밋 `4613115`에 스크립트 변경이 있는 상태(`source.patch`, `env.txt`). Ryzen 7 5700G, JDK 25.0.5, Gatling 3.16.0. 발생기 CPU 0–3·힙 2g, 앱 CPU 4–15·힙 core 2g, realtime·gateway 각 1g, fake-pg 512m. 매회 새 JVM, 외부·내부 예열과 AOT 없음, rate limit 활성, 추적 0.1, 관측 활성, Scouter 비활성. 이번 측정에 한해 `--no-recreate`로 기존 PostgreSQL·Redis·Kafka와 관측 컨테이너를 보호했으며, 실행 중 컨테이너와 Compose 설정의 해시는 모두 일치했다. 기준선에는 Kafka가 없었으므로 추가 브로커의 자원 사용도 비교에 포함된다. 기존 `venue.json`·`members.json`·`schedule-hot-seats.json`은 백업·복원하고 새 공연장을 만들었다. 전체 테스트·check·E2E 뒤 단독 실행했다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --runs 3 --observability --no-scouter
```

## 결과

| 회차 | 정시 준비 / 예매 시도 | 결제 확정 건수 | 확정 좌석 | Gatling 예매 p95 ms | 결제 확정 지연 p95 ms | 기준선 p95 ms | 차이 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1,000 / 1,000 | 63 | 98 | 1,485 | 337 | 425 | -88 |
| 2 | 1,000 / 1,000 | 55 | 97 | 1,711 | 144 | 354 | -210 |
| 3 | 1,000 / 1,000 | 59 | 98 | 1,701 | 127 | 346 | -219 |
| 중앙값 | | 59 | | 1,701 | **144** | **354** | **-210 (-59.2%)** |

회차별 차이는 같은 순번의 독립 실행을 비교한 값이지 짝지은 실험은 아니다. p95는 히스토그램 보간값을 ms로 반올림했다. Gatling p95는 Gatling 자체 assertion 값이며 `gatling-summary.json`은 로그 재집계로 1·3회차에 1ms 차이가 있다. 1,500ms 목표는 그대로이며 1회 통과, 2·3회 미달이다. 01 기준선은 3회 모두 미달이었다. 이 클라이언트 예매 응답시간과 서버 결제 확정 지연은 다른 지표다.

각 회차 정시 준비·등록·입장·예매 시도는 1,000건, 요청 실패 0건, 지각 0건이다. `integrity.json`에서 확정 좌석 중복·판매 좌석 중복·Redis 불일치 모두 0이고 미완료 예매가 없다. 서버의 Gateway와 core 예매 입력도 각각 1,000건이다. 확정 지연 표본 수는 각 회차 확정 예매 수와 일치한다. 원본 `gatling-summary.json`, `readiness.json`, `integrity.json`, `server-metrics-pre.json`, `server-metrics-post.json`, `server-delta.json`, `verdict.json`에 근거한다.

## 차분 검사와 원본 판정

세 회차의 원본 `server-delta.json`은 Kafka consumer의 bytes·records 누적 시계열 각 15개가 없어졌다고 보고해 `valid: false`다. 원본 `verdict.json`도 그 때문에 세 회차 모두 실패이고, 2·3회차는 Gatling 예매 p95도 실패했다. 원본은 수정하지 않았다. 시작 시 값 0인 토픽 미지정 시계열이 이후 같은 소비자 라벨과 토픽 라벨을 가진 시계열로 대체된 것이 원인이다. 이를 구분하도록 차분 도구를 수정해 같은 원본 수집물로 다시 계산한 `server-delta-rechecked.json`은 세 회차 모두 유효하고 입력 대조도 통과한다. 값이 0이 아니거나 동일 소비자 라벨의 토픽별 시계열이 없으면 기존처럼 누락 오류를 낸다. 재계산은 원본 Gatling 실패를 바꾸지 않는다.

## 해석과 한계

결제 확정 지연 중앙값은 기준 354ms에서 144ms로 낮았고, 이번 3회 범위는 127~337ms다. 회차당 55~63건의 작은 표본과 호스트 단독 실행이므로 지연 개선이나 Kafka 설정의 우월성을 인과적으로 단정할 수 없다. 확정 지연은 커밋 전 시각까지이고 실제 PG·분산 호스트 시계 오차는 포함하지 않는다. 직렬화·outbox 활성 또는 다른 파티션 수를 같은 조건으로 비교하지 않았다. 따라서 이번 관측은 현 설정의 정합성과 지연을 확인하는 근거이지 대안보다 낫다는 증명은 아니다. 설정 판단은 [ADR 0043](../../adr/0043-kafka-externalization-step2-decisions.md)에 기록한다.
