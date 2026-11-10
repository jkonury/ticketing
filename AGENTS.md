# ticketing

공연/티켓 예매 서비스(지정석). 1인 학습 겸 포트폴리오 프로젝트. Modulith로 시작해 추후 MSA + k8s로 전환한다.

## 먼저 읽을 문서

- `docs/design.md`: 설계 합의서. 구조·규칙에 대한 결정의 근거
- `docs/roadmap.md`: 단계별 구현 체크리스트와 완료 기준
- `docs/adr/`: 결정별로 검토한 대안과 선택 이유. 설계를 바꾸는 결정은 새 ADR로 남긴다

작업 영역별 세부 지침은 도구에 상관없이 읽을 수 있도록 `docs/rules/`에 둔다. **해당 경로의 파일을 다루기 전에 먼저 읽는다.** (Claude Code와 Codex 공용)

- `docs/rules/backend.md`: `core`, `core-batch`, `realtime`, `gateway`, `fake-pg`
- `docs/rules/frontend.md`: `web`

## 저장소 구조

| 경로 | 내용 |
|---|---|
| `core/` | 코어 Modulith. WebMVC + Virtual Thread, JPA + jOOQ. 루트 패키지 `io.github.ticketing` |
| `core-batch/` | Spring Batch 실행 모듈 (core 코드 공유) |
| `realtime/` | WebFlux + Coroutines + SSE. 대기열. Redis만 사용 |
| `gateway/` | Spring Cloud Gateway. 입장 토큰·SSE 티켓 검증 |
| `fake-pg/` | 가짜 PG 서버 |
| `web/` | Next.js BFF + 화면 |
| `docs/` | 설계서, 로드맵, 측정 결과 |

## 작업 방식

- 작업 시작 전 `docs/roadmap.md`에서 현재 단계와 다음 미완료 항목을 확인한다.
- 한 단계의 **완료 기준**을 만족하기 전에는 다음 단계 항목을 시작하지 않는다.
- 항목을 끝내면 `docs/roadmap.md`의 체크박스를 갱신한다.
- 구현 중 설계와 다르게 가야 할 이유가 생기면 **먼저 사용자에게 알리고**, 합의되면 `docs/design.md`를 같이 고친다. 문서와 코드가 어긋난 채로 두지 않는다.
- `docs/design.md` 12장의 "의도적으로 미룬 것"은 요청 없이 앞당겨 구현하지 않는다.
- 라이브러리 버전과 API는 기억에 의존하지 말고 공식 문서로 확인한다. 특히 Spring Boot 4.x, Spring Modulith, Spring Cloud Gateway, jOOQ, Next.js.

## 반드시 지킬 설계 규칙

1. 코어 모듈 간 호출은 Modulith 이벤트로만 한다. 다른 모듈의 내부 클래스를 직접 참조하지 않는다.
2. 모듈별 PostgreSQL 스키마를 넘는 외래 키와 조인을 만들지 않는다.
3. `realtime`은 PostgreSQL에 접근하지 않는다.
4. 코어는 대기열을 모른다. 입장 토큰 검증은 Gateway의 책임이다.
5. JWT는 브라우저에 노출하지 않는다. 토큰은 BFF 서버 측에만 둔다.
6. 좌석 확정의 최종 방어선은 DB의 `(회차, 좌석)` 유니크 제약이다. Redis 선점만 믿고 확정하지 않는다.
7. 결제 요청과 웹훅 처리는 멱등 키로 중복을 걸러낸다.

## 명령

M0(프로젝트 뼈대) 완료 후 유효하다. 실제 구성이 달라지면 이 절을 고친다.

```bash
docker compose up -d                 # PostgreSQL, Redis, fake-pg
./gradlew build                      # 전체 빌드 + 테스트
./gradlew :core:bootRun
cd web && npm run dev
```

### 테스트 실행

- 사전 조건: Docker가 떠 있어야 한다. jOOQ 코드 생성(`:core:generateJooq`)과 Testcontainers PostgreSQL이 Docker를 쓴다. 에이전트 스크립트는 `fd`, `rg`를 쓰므로 설치되어 있어야 한다.
- Gradle 테스트는 모듈 단위다. `--tests` 필터는 항상 모듈 경로와 함께 쓴다(`:core:test --tests ...`). 경로 없이 쓰면 일치하는 테스트가 없는 모듈에서 실패한다.
- 에이전트나 긴 로그를 줄여 보고 싶을 때는 `scripts/agent/test/`의 래퍼를 쓴다. 실패한 테스트와 Gradle 오류만 출력하고 전체 로그 경로를 알려준다.

| 목적 | 명령 |
|---|---|
| 전체 테스트 | `./gradlew test` |
| 모듈 테스트 | `./gradlew :core:test` |
| 단일 클래스 | `./gradlew :core:test --tests "*BookingConcurrencyTest"` |
| 실패만 요약 (테스트) | `scripts/agent/test/run-tests.sh :core:test --tests "*BookingConcurrencyTest"` |
| 검증 태스크 요약 (컴파일·테스트·검사) | `scripts/agent/test/check-summary.sh :core:check` |
| 명령 로그 요약 (임의 명령) | `scripts/agent/test/run-summary.sh -- ./gradlew :core:compileKotlin` |

- `check-summary.sh`는 `test` 태스크와 `--tests`를 받지 않는다. 테스트 실행은 `run-tests.sh`를 쓴다.
- 실패 보고: 테스트가 실패하면 실패한 그대로 보고한다. 로그의 실패 메시지 일부와 실패한 테스트 이름을 함께 전달한다. 실패를 우회하려고 테스트를 지우거나 `@Disabled` 처리하지 않는다.
- Modulith 구조 검증은 모듈 의존을 바꿨을 때 필수로 돌린다(`docs/rules/backend.md`). 테스트 규칙(한국어 백틱 이름, `Clock` 고정, `runTest`)도 같은 문서를 따른다.

## 공통 규칙

- 대화, 문서, 커밋 메시지는 한국어. 코드 식별자는 영어.
- 주석은 "왜"가 코드에서 드러나지 않을 때만 쓴다.
- 커밋은 사용자가 요청할 때만 한다. 커밋 메시지에 도구 서명(attribution) 줄을 넣지 않는다.
- 커밋 메시지 형식: `<type>(<scope>): <요약>`. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. scope는 모듈명(`booking`, `gateway`, `web` 등).
- 테스트가 실패하면 실패한 그대로 보고한다. 테스트를 지우거나 비활성화해서 통과시키지 않는다.
- 비밀 값은 저장소에 넣지 않는다. 로컬 설정은 `.env`(git 제외)와 `application-local.yml`을 쓴다.
