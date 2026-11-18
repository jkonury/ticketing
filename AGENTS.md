# ticketing

공연/티켓 예매 서비스(지정석). 1인 학습 겸 포트폴리오 프로젝트. Modulith로 시작해 추후 MSA + k8s로 전환한다.

## 1. 먼저 읽을 문서

- `docs/architecture.md`: 아키텍처 문서. 구조·규칙에 대한 결정의 근거
- `docs/roadmap.md`: 단계별 구현 체크리스트와 완료 기준
- `docs/adr/`: 결정별로 검토한 대안과 선택 이유. 설계를 바꾸는 결정은 새 ADR로 남긴다

작업 영역별 세부 지침은 도구에 상관없이 읽을 수 있도록 `docs/rules/`에 둔다. **해당 경로의 파일을 다루기 전에 먼저 읽는다.** (Claude Code와 Codex 공용)

- `docs/rules/backend.md`: `core`, `core-batch`, `realtime`, `gateway`, `fake-pg`
- `docs/rules/frontend.md`: `web`
- `docs/rules/herdr.md`: herdr(터미널 워크스페이스) 명령 정리. 프롬프트에서 herdr 사용을 지시받으면 검색하지 말고 이 문서를 먼저 읽는다
- `docs/rules/herdr-workflow.md`: 메인 세션이 구현자·리뷰어 에이전트에게 위임하는 오케스트레이션 절차와 `scripts/agent/herdr/` 스크립트. 사용자가 위임을 명시적으로 지시할 때만 쓰며, 그때 먼저 읽는다

## 2. 저장소 구조

| 경로 | 내용 |
|---|---|
| `core/` | 코어 Modulith. WebMVC + Virtual Thread, JPA + jOOQ. 루트 패키지 `io.github.ticketing` |
| `core-batch/` | Spring Batch 실행 모듈 (core 코드 공유) |
| `realtime/` | WebFlux + Coroutines + SSE. 대기열. Redis만 사용 |
| `gateway/` | Spring Cloud Gateway. 입장 토큰·SSE 티켓 검증 |
| `fake-pg/` | 가짜 PG 서버 |
| `web/` | Next.js BFF + 화면 |
| `docs/` | 설계서, 로드맵, 측정 결과 |

## 3. 작업 방식

- 작업 시작 전 `docs/roadmap.md`에서 현재 단계와 다음 미완료 항목을 확인한다.
- 한 단계의 **완료 기준**을 만족하기 전에는 다음 단계 항목을 시작하지 않는다.
- 항목을 끝내면 `docs/roadmap.md`의 체크박스를 갱신한다.
- 구현 중 설계와 다르게 가야 할 이유가 생기면 **먼저 사용자에게 알리고**, 합의되면 `docs/architecture.md`를 같이 고친다. 문서와 코드가 어긋난 채로 두지 않는다.
- `docs/roadmap.md`의 "단계와 무관한 후속 항목"(의도적으로 미룬 것)은 요청 없이 앞당겨 구현하지 않는다.
- 라이브러리 버전과 API는 기억에 의존하지 말고 공식 문서로 확인한다. 특히 Spring Boot 4.x, Spring Modulith, Spring Cloud Gateway, jOOQ, Next.js.

## 4. 반드시 지킬 설계 규칙

1. 코어 모듈 간 호출은 Modulith 이벤트로만 한다. 다른 모듈의 내부 클래스를 직접 참조하지 않는다.
2. 모듈별 PostgreSQL 스키마를 넘는 외래 키와 조인을 만들지 않는다.
3. `realtime`은 PostgreSQL에 접근하지 않는다.
4. 코어는 대기열을 모른다. 입장 토큰 검증은 Gateway의 책임이다.
5. JWT는 브라우저에 노출하지 않는다. 토큰은 BFF 서버 측에만 둔다.
6. 좌석 확정의 최종 방어선은 DB의 `(회차, 좌석)` 유니크 제약이다. Redis 선점만 믿고 확정하지 않는다.
7. 결제 요청과 웹훅 처리는 멱등 키로 중복을 걸러낸다.

## 6. 명령

M0(프로젝트 뼈대) 완료 후 유효하다. 실제 구성이 달라지면 이 절을 고친다.

```bash
docker compose up -d                 # PostgreSQL, Redis, Kafka (fake-pg는 scripts/dev/run.sh --fake-pg)
docker compose -f compose.yaml -f compose.observability.yaml up -d   # 관측 스택 (Prometheus, Tempo, Grafana 13001)
./scripts/loadtest/run.sh --scenario <smoke|queue-surge|hot-seats>  # 부하 테스트 (k6, Gateway 18000 호출)
./gradlew :loadtest-gatling:gatlingRun --simulation io.github.ticketing.loadtest.<Simulation>  # Gatling 부하 시나리오 (loadtest/gatling/README.md)
./gradlew build                      # 전체 빌드 + 테스트
./gradlew :core:bootRun
./gradlew :realtime:bootRun
./gradlew :gateway:bootRun           # BFF·클라이언트는 Gateway(18000)를 거쳐 호출
# 최초 1회: web 환경 변수 파일 생성
cp web/.env.example web/.env.local
cd web && pnpm install && pnpm dev
```

## 7. 테스트 실행

**Claude Code와 Codex 모두** 저장소 루트에서 다음 스크립트를 실행합니다. 실패를 받으면 작업 중인 에이전트가 직접 고칩니다.

- 사전 조건: Docker가 떠 있어야 한다. jOOQ 코드 생성(`:core:generateJooq`)과 Testcontainers PostgreSQL·Kafka가 Docker를 쓴다. 로컬 core 결제 흐름에는 Compose Kafka(`127.0.0.1:19092`)가 필요하다. 에이전트 스크립트는 `fd`, `rg`를 쓰므로 설치되어 있어야 한다.
- test: `./scripts/agent/test/run-tests.sh` (필터는 사용자가 지정한 경우에만 `--tests "패턴"` 추가)
- check: `./scripts/agent/test/check-summary.sh` (기본 `check`, 필요하면 Gradle 작업을 인자로 지정)
- 품질 검사: Kotlin 변경 후 `ktlintCheck detekt`를 통과시킨다(`./gradlew ktlintFormat`으로 포맷 자동 수정). 작업 종료 시 Stop hook(`scripts/agent/hook/stop_quality.sh`, Claude Code·Codex 공용)과 커밋 시 `.githooks/pre-commit`이 같은 검사를 하며, 클론 후 `./scripts/setup-hooks.sh`를 한 번 실행해 활성화한다. `@Suppress`·baseline 추가·`--no-verify`로 우회하지 않는다.
- web 변경 후 `./scripts/agent/test/web-check.sh`(빌드 확인은 `--build`)를 통과시킨다. Stop hook·pre-commit이 같은 검사를 한다.
- 같은 작업 디렉터리의 test와 check는 순차 실행한다. 동시에 실행하면 Gradle 테스트 결과 파일이 충돌할 수 있다. 성능 측정도 다른 Gradle 검사가 끝난 뒤 단독 실행한다.
- 문서 검사: `./scripts/agent/docs/check-docs.sh [경로...]` (lychee 오프라인으로 상대 경로 링크와 `#anchor`를, `check-doc-refs.sh`로 문서의 백틱 경로를 검사). 문서를 옮기거나 제목·링크를 바꾸면 실행합니다. 설정은 `lychee.toml`입니다.
- CI: `.github/workflows/ci.yml`이 push(main)·pull_request마다 `backend`(`./gradlew build`, ktlint·detekt 포함), `web`(lint·typecheck·test·format:check·build), `docs`(lychee·`check-doc-refs.sh`)를 병렬로 돌린다. E2E·부하 테스트는 포함하지 않는다.
- 실패 보고: 테스트가 실패하면 실패한 그대로 보고한다. 로그의 실패 메시지 일부와 실패한 테스트 이름을 함께 전달한다. 실패를 우회하려고 테스트를 지우거나 `@Disabled` 처리하지 않는다.
- E2E: `./scripts/e2e/run.sh [--keep] [--tests "패턴"]`. `core-batch` bootJar도 빌드하며 Batch E2E(`BatchBaselineE2eTests`)가 별도 프로세스로 실행한다. 사전 조건은 `.env`, 실행 중인 Docker, 비어 있는 18000·18080·18081·18082·18090 포트다. 로그는 `build/e2e/logs/`에 저장한다. 스크립트가 실행마다 관리자(ADMIN) 계정을 가입 후 DB에서 승격해 `E2E_ADMIN_EMAIL`·`E2E_ADMIN_PASSWORD`로 전달한다(앱 코드에 E2E 전용 경로 없음). `--web`은 Playwright web 앱을 포트 13000에 추가하므로 해당 포트도 비어 있어야 한다. `build`·`check`에는 포함되지 않으므로 core·gateway·realtime 흐름을 바꾼 작업에서 실행한다.
- 부하 테스트(`scripts/loadtest/run.sh`)는 `build`·`check`에 포함되지 않으며, 다른 Gradle 작업과 동시에 실행하지 않는다. 결과는 `build/loadtest/runs/`에 저장한다.
- Modulith 구조 검증은 모듈 의존을 바꿨을 때 필수로 돌리고, 다이어그램은 `./gradlew :core:modulithDocs`로 생성한다(`docs/rules/backend.md`). 테스트 규칙(한국어 백틱 이름, `Clock` 고정, `runTest`)도 같은 문서를 따른다.

## 8. 공통 규칙

- 대화, 문서, 커밋 메시지는 한국어. 코드 식별자는 영어.
- 주석은 "왜"가 코드에서 드러나지 않을 때만 쓴다.
- 커밋은 사용자가 요청할 때만 한다. 커밋 메시지에 도구 서명(attribution) 줄을 넣지 않는다.
- 커밋 메시지 형식: `<type>(<scope>): <요약>`. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. scope는 모듈명(`booking`, `gateway`, `web` 등).
- 테스트가 실패하면 실패한 그대로 보고한다. 테스트를 지우거나 비활성화해서 통과시키지 않는다.
- 비밀 값은 저장소에 넣지 않는다. 로컬 설정은 `.env`(git 제외)와 `application-local.yml`을 쓴다.

## 9. 조회·출력 분량 관리

- **필수 지침은 처음에 모두 읽고, 문서·코드는 필요한 범위만 읽습니다.** `rg --files`로 경로를 찾고 `rg -n`으로 관련 절·심볼을 찾은 뒤 `sed -n '시작,끝p' 파일`로 해당 구간을 확인합니다. 대용량 파일이나 여러 문서를 한꺼번에 `cat`하지 않습니다.
- **한 번 읽은 내용은 재사용합니다.** 확인한 경로·행·결론을 작업 중 짧게 기록하고, 같은 문서·코드·diff는 변경됐거나 새 의문이 생긴 경우에만 다시 조회합니다. 다시 볼 때는 달라진 구간이나 의문과 관련된 부분만 읽습니다.
- **diff는 범위를 좁혀 확인합니다.** 먼저 `git diff --stat`·`git status --short`로 범위를 확인하고 `git diff -- 경로`로 검토합니다. 같은 전체 diff를 중간 점검마다 출력하지 않습니다. 새 파일은 필요한 구간을 별도로 확인합니다.
- **테스트·로그는 요약 스크립트를 우선 사용합니다.** 7절의 전용 스크립트가 있으면 그것을 쓰고, 그 밖의 명령은 `run-summary.sh`로 실행합니다. 출력이 많다는 이유로 필수 검사나 실패 처리를 생략하지 않습니다. pipeline은 `bash -o pipefail -c '...'`로 감싸 실패가 가려지지 않게 합니다.
- **실패 분석은 저장된 로그에서 좁혀 갑니다.** 성공 로그·전체 trace를 대화에 출력하지 않습니다. 먼저 실패 요약을 보고, 부족하면 해당 로그에서 `rg -n -m 20 '오류 패턴' 로그경로`로 위치를 찾은 뒤 필요한 주변 줄만 조회합니다. 끝부분만으로 원인을 알 수 없으면 앞부분과 원인 예외도 확인합니다. 수정·새 근거 없이 같은 검사를 반복 실행하지 않습니다.
- **도구 출력은 호출 전에 제한합니다.** 검색 경로·패턴·문맥 줄 수와 출력 한도를 정하고, 잘렸다면 같은 명령을 더 큰 한도로 반복하지 않고 파일·구간을 좁힙니다. 완료 보고에는 변경 내용, 검증 결과와 건너뛴 항목, 필요한 로그 경로만 적습니다.
