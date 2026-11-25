# ticketing

공연/티켓 예매 서비스(지정석). 1인 학습 겸 포트폴리오 프로젝트. Modulith로 시작해 추후 MSA + k8s로 전환한다.

## 1. 먼저 읽을 문서

- `docs/architecture.md`: 아키텍처 문서. 구조·규칙에 대한 결정의 근거
- `docs/roadmap.md`: 단계별 구현 체크리스트와 완료 기준
- `docs/adr/`: 결정별로 검토한 대안과 선택 이유. 설계를 바꾸는 결정은 새 ADR로 남긴다

작업 영역별 세부 지침은 도구에 상관없이 읽을 수 있도록 `docs/rules/`에 둔다. **해당 경로의 파일을 다루기 전에 먼저 읽는다.** (Claude Code와 Codex 공용)

- [백엔드 공통 지침](docs/rules/backend.md): 공통 작성 규칙과 `core`, `core-batch`, `realtime`, `gateway`, `fake-pg`, `common`, `contract`별 지침. 변경 경로에 해당하는 지침도 읽는다.
- [백엔드 테스트 지침](docs/rules/backend-testing.md): 백엔드 테스트·픽스처·E2E·부하 테스트·CI를 다룰 때 읽는다.
- [프론트엔드 지침](docs/rules/frontend.md): `web`(Next.js BFF + 화면)을 다룰 때 읽는다.
- 리뷰: 백엔드 변경은 [백엔드 리뷰 지침](docs/rules/backend-review.md), `web` 변경은 [프론트엔드 리뷰 지침](docs/rules/frontend-review.md)을 따른다.
- [서브에이전트 위임 지침](docs/rules/subagent-delegation.md): 구현 계획을 서브에이전트 task로 나눠 위임할 때 읽는다.
- 반복 작업 절차는 skill로 둔다. 원본은 `.agents/skills/<이름>/SKILL.md`(Codex가 읽음)이고 `.claude/skills/<이름>`은 그 symlink다. 추가·수정 후 `./scripts/agent/skills/check-skills.sh`를 실행한다. skill에는 순서만 쓰고 규범은 `docs/rules`를 링크한다.
- `docs/rules/herdr.md`: herdr(터미널 워크스페이스) 명령 정리. 프롬프트에서 herdr 사용을 지시받으면 검색하지 말고 이 문서를 먼저 읽는다
- `docs/rules/herdr-workflow.md`: 메인 세션이 구현자·리뷰어 에이전트에게 위임하는 오케스트레이션 절차와 `scripts/agent/herdr/` 스크립트. 사용자가 위임을 명시적으로 지시할 때만 쓰며, 그때 먼저 읽는다

## 2. 저장소 구조

| 경로 | 내용 |
|---|---|
| `core/` | 코어 Modulith. WebMVC + Virtual Thread, JPA + jOOQ. 루트 패키지 `io.github.ticketing` |
| `core-batch/` | Spring Batch 실행 모듈 (core 코드 공유) |
| `payment/` | 결제 앱. WebMVC + Virtual Thread, 포트 18085, 전용 DB `payment_db` |
| `payment-batch/` | 결제 대사 Batch 실행 모듈 (payment 코드 공유, `payment_db`) |
| `realtime/` | WebFlux + Coroutines + SSE. 대기열. Redis만 사용 |
| `gateway/` | Spring Cloud Gateway. 입장 토큰·SSE 티켓 검증 |
| `fake-pg/` | 가짜 PG 서버 |
| `common/` | JVM 앱에서 사용하는 공통 코드 |
| `contract/` | 프로세스 간 이벤트 계약 |
| `web/` | Next.js BFF + 화면 |
| `docs/` | 설계서, 로드맵, 측정 결과 |

## 3. 작업 방식

- 작업 시작 전 `docs/roadmap.md`에서 현재 단계와 다음 미완료 항목을 확인한다.
- 한 단계의 **완료 기준**을 만족하기 전에는 다음 단계 항목을 시작하지 않는다.
- 항목을 끝내면 `docs/roadmap.md`의 체크박스를 갱신한다.
- 구현 중 설계와 다르게 가야 할 이유가 생기면 **먼저 사용자에게 알리고**, 합의되면 `docs/architecture.md`를 같이 고친다. 문서와 코드가 어긋난 채로 두지 않는다.
- `docs/roadmap.md`의 "단계와 무관한 후속 항목"(의도적으로 미룬 것)은 요청 없이 앞당겨 구현하지 않는다.
- 세션을 넘길 때는 [인계 템플릿](docs/templates/session-handoff.md) 형식으로 `.handoff/`(git 제외)에 인계 문서를 쓴다.
- 라이브러리 버전과 API는 기억에 의존하지 말고 공식 문서로 확인한다. 특히 Spring Boot 4.x, Spring Modulith, Spring Cloud Gateway, jOOQ, Next.js.

## 4. 반드시 지킬 설계 규칙

1. 코어 모듈 간 호출은 Modulith 이벤트로만 한다. 다른 모듈의 내부 클래스를 직접 참조하지 않는다.
2. 모듈별 PostgreSQL 스키마를 넘는 외래 키와 조인을 만들지 않는다.
3. `realtime`은 PostgreSQL에 접근하지 않는다.
4. 코어는 대기열을 모른다. 입장 토큰 검증은 Gateway의 책임이다.
5. JWT는 브라우저에 노출하지 않는다. 토큰은 BFF 서버 측에만 둔다.
6. 좌석 확정의 최종 방어선은 DB의 `(회차, 좌석)` 유니크 제약이다. Redis 선점만 믿고 확정하지 않는다.
7. 결제 요청과 웹훅 처리는 멱등 키로 중복을 걸러낸다.

## 5. 명령

실제 구성이 달라지면 이 절을 고친다.

```bash
docker compose up -d                 # PostgreSQL, Redis, Kafka (fake-pg는 scripts/dev/run.sh --fake-pg)
docker compose -f compose.yaml -f compose.observability.yaml up -d   # 관측 스택 (Prometheus, Tempo, Grafana 13001)
./scripts/loadtest/run.sh --scenario <smoke|queue-surge|hot-seats|seat-conflict-refund|coupon-drop>  # k6 부하 테스트 (Gateway 18000 호출)
./gradlew :loadtest-gatling:gatlingRun --simulation io.github.ticketing.loadtest.<Simulation>  # Gatling 부하 시나리오 (loadtest/gatling/README.md)
./gradlew build                      # 전체 빌드 + 테스트
./gradlew :core:bootRun
./gradlew :payment:bootRun          # 결제 앱(18085). fake-pg 웹훅이 직접 호출. scripts/dev/run.sh --payment
./gradlew :realtime:bootRun
./gradlew :gateway:bootRun           # BFF·클라이언트는 Gateway(18000)를 거쳐 호출
# 최초 1회: web 환경 변수 파일 생성
cp web/.env.example web/.env.local
cd web && pnpm install && pnpm dev
```

## 6. 테스트와 검사

**Claude Code와 Codex 모두** 저장소 루트에서 다음 스크립트를 실행한다. 실패를 받으면 작업 중인 에이전트가 직접 고친다.

- 사전 조건: Docker가 떠 있어야 한다(jOOQ 코드 생성과 Testcontainers가 사용한다). 에이전트 스크립트는 `fd`, `rg`를 쓴다.
- 변경 범위 검사: `./scripts/agent/test/verify-changed.sh [--dry-run] [기준 ref | A..B]`. 변경 경로에 맞는 아래 검사를 골라 실행하고 E2E·`modulithDocs` 필요 여부를 알린다. 작업을 마치기 전에 실행한다.
- test: `./scripts/agent/test/run-tests.sh` (필터는 사용자가 지정한 경우에만 `--tests "패턴"` 추가)
- check: `./scripts/agent/test/check-summary.sh` (기본 `check`, 필요하면 Gradle 작업을 인자로 지정)
- Kotlin 변경 후 `ktlintCheck detekt`를 통과시킨다(`./gradlew ktlintFormat`으로 포맷 자동 수정). `@Suppress`·baseline 추가는 사용자와 합의한 경우에만 하고, `--no-verify`로 우회하지 않는다.
- web 변경 후 `./scripts/agent/test/web-check.sh`(빌드 확인은 `--build`)를 통과시킨다.
- 문서를 옮기거나 제목·링크를 바꾸면 `./scripts/agent/docs/check-docs.sh [경로...]`를 실행한다.
- `.githooks/pre-commit`이 스테이지된 Kotlin·web 변경에 대해 같은 품질 검사를 커밋 시점에 실행한다. 클론 후 `./scripts/setup-hooks.sh`를 한 번 실행한다.
- 같은 작업 디렉터리의 Gradle test·check·성능 측정은 순차 실행한다. 동시에 실행하면 결과 파일이 충돌한다.
- 모듈 의존을 바꾸면 Modulith 구조 검증을 돌리고 `./gradlew :core:modulithDocs`로 다이어그램을 다시 생성한다. [core 작성 지침](docs/rules/backend-core.md)
- 실패하면 실패한 그대로 보고한다(실패한 테스트 이름과 메시지 일부). 실패를 우회하려고 테스트를 지우거나 `@Disabled` 처리하지 않는다.
- E2E(`scripts/e2e/run.sh`)·부하 테스트·CI는 `build`·`check`에 포함되지 않는다. core·gateway·realtime 흐름을 바꾼 작업에서는 E2E를 실행한다. 사전 조건, 포트, 옵션은 [E2E·부하·CI 실행](docs/rules/backend-testing.md#e2e부하ci-실행)을 따른다.

## 7. 공통 규칙

- 대화, 문서, 커밋 메시지는 한국어. 코드 식별자는 영어.
- 주석은 "왜"가 코드에서 드러나지 않을 때만 쓴다.
- 커밋은 사용자가 요청할 때만 한다. 단, herdr 워크플로 위임 지시는 main 세션의 task별 커밋 요청으로 본다([herdr-workflow.md](docs/rules/herdr-workflow.md#2-실행-범위)). 커밋 메시지에 도구 서명(attribution) 줄을 넣지 않는다.
- 커밋 메시지 형식: `<type>(<scope>): <요약>`. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. scope는 모듈명(`booking`, `gateway`, `web` 등).
- 비밀 값은 저장소에 넣지 않는다. 로컬 설정은 `.env`(git 제외)와 `application-local.yml`을 쓴다.

## 8. 조회·출력 분량 관리

- 필수 지침은 처음에 모두 읽고, 문서·코드는 필요한 범위만 읽는다. `rg --files`·`rg -n`으로 위치를 찾고 `sed -n '시작,끝p'`로 해당 구간만 본다. 대용량 파일이나 여러 문서를 한꺼번에 `cat`하지 않는다.
- 한 번 읽은 내용은 재사용한다. 같은 문서·코드·diff는 변경됐거나 새 의문이 생긴 경우에만 다시 보고, 그때도 관련 구간만 본다. diff는 `git diff --stat`으로 범위를 본 뒤 `git diff -- 경로`로 좁힌다.
- 테스트·로그는 6절의 요약 스크립트를 우선 쓰고, 그 밖의 명령은 `scripts/agent/test/run-summary.sh [-C 디렉터리] -- 명령`으로 실행한다. pipeline은 `bash -o pipefail -c '...'`로 감싼다.
- 실패 분석은 저장된 로그에서 `rg -n -m 20 '오류 패턴' 로그경로`로 좁혀 간다. 성공 로그·전체 trace를 출력하지 않고, 수정이나 새 근거 없이 같은 검사를 반복하지 않는다.
- 도구 출력은 호출 전에 한도를 정한다. 완료 보고에는 변경 내용, 검증 결과와 건너뛴 항목, 필요한 로그 경로만 적는다.

## 9. 모델 분담

- rg 한 번으로 끝나는 탐색은 메인에서 직접 처리하고, 여러 디렉터리·모듈·호출 관계를 조사해야 할 때만 위임한다.
- 파일 탐색과 검색은 Explore, 테스트와 빌드 실행은 runner에 맡긴다. 결과는 결론과 `경로:줄번호`만 받는다.
- 빠짐없이 찾아야 하는 탐색(리팩터링 전 전체 사용처 등)은 검색 결과 원문을 함께 받거나 메인에서 직접 확인한다.
- 서브에이전트 보고 가운데 결정의 근거로 쓰는 사실은 해당 파일을 직접 열어 한 번 확인한다.
- 구현 task를 나눠 위임하는 방법은 [서브에이전트 위임 지침](docs/rules/subagent-delegation.md)을 따른다.
