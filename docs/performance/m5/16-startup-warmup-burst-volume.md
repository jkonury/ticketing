# M5 내부 예열 HTTP 호출량 확대

> 허브: [M5-2 개선 및 측정 인계](../m5-improvements.md) · 이전: [최초 내부 예열 검증](15-layered-startup-warmup.md) · 결정: [ADR 0030](../../adr/0030-startup-warmup-burst-volume.md)

## 결론

Gateway 경유 예열을 8건에서 외부 예열의 예매 요청량과 같은 **15회 × 1,000건**으로 확대하고 실제 HTTP 본문 캐시·DTO 변환·검증을 추가했다. 모든 검증 실행에서 15,000건을 완료했고 기능 검사도 통과했다. 다만 JFR core Interpreted 표본 23.1%로 외부 예열 참고값 0/58에 도달하지 못했으며, JFR OFF p95 **741·843·853ms(중앙값 843ms)**로 300ms 목표는 미달이다. 호출 강도 확대와 외부 전체 흐름의 준비 상태 동등성을 구분한다. 순수 콜드 회귀는 사용자 요청대로 생략했다.

## 변경

2026-11-16 사용자 요청으로 최초 내부 예열 구현·검증을 `faeb0cc`로 커밋한 뒤 예열 강도를 확대했다.

- Gateway 실제 HTTP 경유: 8건 순차 → 15회 × 1,000건, 회차 내 동시 요청 상한 1,000. 승자 5%·패자 95%다. 회차가 끝나야 다음 회차를 시작한다.
- core 대기를 포함한 Gateway 전체 예열 상한: 30초 → 120초. core 직접 예열 200회·30초 상한은 유지한다.
- 서버 전용 예열 경로: 생성 후 3분·최대 30,000회·동시 1,000개. DB 롤백·예열 전용 Redis 네임스페이스·30초 TTL·정리는 유지한다.
- 예열 발생기 전용 HTTP 연결 풀을 설정한 동시성에 맞추고 종료 시 폐기한다. 운영 Gateway→core 풀과 DB 풀 설정은 유지한다.
- Gateway의 운영 본문 캐시·파서를 공유하고 core에서 실제 예매 DTO를 JSON 본문으로 바인딩·검증한다. 고정 템플릿만 허용하며 DB·Redis에서 쓸 식별자는 서버가 생성한다.
- 본 측정 시작 전 core·Gateway 예열의 `completed`를 확인한다. 미완료면 앱의 기동 계속 정책과 별도로 부하 측정을 실패 처리한다.

외부 예열의 예매 생성 호출량에 맞춘 변경이다. 대기열·SSE·결제·공유 좌석 경합 전체는 내부 예열에 추가하지 않았다. 호출 수가 같아도 JIT 상태와 p95가 같다고 가정하지 않는다.

## 검증

첫 호출량 확대 E2E는 통과했다. Gateway는 24,172ms(core 대기 포함)에 15회·15,000건·승자 750건·DB 15,750회·Redis 74,250회를 완료했다. core 직접 예열은 6,417ms·200회·승자 10회·DB 210회·Redis 990회다. 예열 데이터 잔존은 0건이었다.

### 중간 진단: 본문 바인딩 보강 전

`20261116-132127-hot-seats/1`에서 JFR을 켠 진단을 수행했다. 전체/성공 예매 p95는 944/730ms로 미달했고 정시 준비 1,000명·오류·SSE·정합성은 통과했다. Gateway는 26,522ms에 15,000건 예열을 완료했다. core 최상위 Interpreted 표본은 44/198(22.2%), Gateway는 5/161(3.1%)다. 요청 본문 변환·검증 관련 스택이 남아 실제 JSON 본문과 MVC 바인딩·검증, Gateway 본문 캐시 경유를 보강했다. 이 중간 진단을 최종 코드의 성능 판정에 합치지 않는다.

### 최종 코드의 기능 검사·JFR 진단

본문 바인딩 보강 후 필터 없는 전체 테스트와 `ktlintCheck detekt`, 예열 활성 E2E가 통과했다. 가상 시간 테스트에서 회차 간 순서·미완료 요청 상한·전체 timeout 취소 전파를 확인했다. E2E Gateway 예열은 25,710ms(core 대기 포함)에 15회·15,000건을 완료했다.

`20261116-132736-hot-seats/1`의 최종 JFR 진단은 전체/성공 예매 p95 **917/863ms**로 300ms 미달이다. 정시 준비 1,000/1,000, 예상 밖 오류·429 0건, SSE 1,000/1,000, 중복 판매·DB/Redis 불일치 0건이다. Gateway 예열은 28,064ms에 15,000건·승자 750건을 완료했다. DB 15,750회·Redis 74,250회다.

| 앱 | 최상위 Java 프레임이 Interpreted인 표본 | 비율 |
|---|---|---|
| core | 45/195 | 23.1% |
| Gateway | 3/162 | 1.9% |

분석 구간은 기존과 같이 Gatling 원시 로그의 예매 요청 시작~마지막 응답 구간이며 모든 앱 스레드를 포함한다. core 표본에는 `RequestPaymentService.request` 등 예열에서 제외한 결제 코드도 있다. 표본·지연 사이 인과나 특정 경로의 지연 기여율을 확정하지 않는다. core는 기존 외부 예열 참고값 0/58에 도달하지 못했다. 메모리·HTTP 예열을 늘린 것만으로 외부 전체 흐름과 같은 준비 상태를 보장할 수 없다는 한계가 남는다.

JFR 분석은 `build/cold-jfr-diagnosis/`의 기존 코드를 재사용했고 결과는 `burst15000-core.tsv`·`burst15000-gateway.tsv`(중간), `burst15000-body-core.tsv`·`burst15000-body-gateway.tsv`(최종)로 분리했다. 과거 결과를 덮어쓰지 않았다. JFR 분석과 Gradle 검사는 부하 측정과 동시에 실행하지 않았다.

### JFR OFF 성능 판정

Gatling 힙 2g·1,000명·관측 ON·Scouter OFF·발생기 CPU 0-3 / 앱 4-15·기존 풀 설정으로 앱을 매번 새로 기동했다. 외부 예열·AOT·컴파일 임계 변경은 없다. 결과는 원시 로그 재집계값이며 진단 JFR과 합치지 않는다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g \
  --runs 3 --observability --no-scouter --internal-warmup \
  --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15
```

| 실행 디렉터리 접두사 / 회차 | 전체 / 성공 예매 p95 | 판정 |
|---|---|---|
| `20261116-133016` / 1 | 741 / 710ms | 300ms 미달 |
| `20261116-133016` / 2 | 843 / 784ms | 300ms 미달 |
| `20261116-133016` / 3 | 853 / 777ms | 300ms 미달 |

세 회차 모두 정시 준비 1,000/1,000, 예상 밖 오류·429 0건, SSE 1,000/1,000, 중복 판매·DB/Redis 불일치 0건이다. 전체·성공 예매 p95와 도구 종료 코드만 판정 미달이다. Gatling 종료 코드는 각 2, 러너 종료 코드는 1이다. 콘솔 p95는 742·843·853ms로 원시 로그 재집계값과 구분한다.

| 회차 | core 직접 예열 시간 | Gateway 예열 시간(core 대기 포함) |
|---|---|---|
| 1 | 6,516ms | 27,088ms |
| 2 | 6,541ms | 26,514ms |
| 3 | 6,493ms | 25,972ms |

모든 회차 core는 직접 200회·승자 10회·DB 210회·Redis 990회, Gateway는 15회·15,000건·승자 750건·DB 15,750회·Redis 74,250회로 완료했다. 각 앱 메모리 반복은 10,000회다. `env.txt`에 내부 예열 여부·기동 집계가 남고 `verdict.json`에 판정이 남는다. 기존 `warmup=false` 필드는 외부 예열 비활성을 뜻한다.

종료 후 예열 전용 Redis 키와 DB 음수 회차 예매는 모두 0건이었다. 포트 충돌을 피하려고 정지한 E2E용 fake-pg 컨테이너를 승인된 절차대로 복원했다. 순수 콜드 회귀 생략 요청은 유지하며 300ms 목표는 체크하지 않는다. 운영 풀·컴파일 옵션·인스턴스 수를 추가 변경하지 않았다.

## 검사와 산출물

- 전체 테스트: `build/run-tests/gradle-test-out.txt`(통과).
- 품질 검사: `build/check-summary/gradle-out.txt`(통과). 중간 MagicNumber·ThrowsCount 위반은 코드 정리로 해결했으며 Suppress·baseline은 추가하지 않았다.
- 최종 예열 활성 E2E: `build/e2e/logs/`, 요약 `/tmp/ticketing-command.e76FDDQy`(통과).
- 중간 JFR 러너: `/tmp/ticketing-command.3lsah8MF`, 최종 JFR 러너: `/tmp/ticketing-command.KBpvCMV3`(성능 기준 미달).
- JFR OFF 3회 러너: `/tmp/ticketing-command.LRRAfKba`(성능 기준 미달).
- 실행 디렉터리는 `build/loadtest/runs/<접두사>-hot-seats/<회차>`이며 실패 산출물도 보존했다.
- 최종 셸 구문·Python 회귀·`git diff --check`와 문서 링크·경로 검사 통과.
- web·모듈 간 의존 변경이 없어 web 검사·Modulith 다이어그램 재생성은 생략했다.
