# 온보딩

처음 저장소를 받은 사람이 로컬에서 전체 스택을 띄우고 첫 변경을 검증하기까지의 안내다. 운영·장애 대응 절차는 [runbook.md](./runbook.md)를 본다.

## 1. 프로젝트 한 줄 요약

지정석 공연 티켓 예매 서비스다. Modulith로 시작해 MSA + k8s로 전환하는 1인 학습·포트폴리오 프로젝트이며 대기열, 좌석 선점, 결제, 정산을 다룬다. 전체 그림은 [architecture.md](./architecture.md), 용어는 [glossary.md](./glossary.md)를 본다.

## 2. 사전 준비

| 도구 | 용도 |
|---|---|
| JDK 25 | 백엔드 빌드·실행 (버전 상세는 [architecture.md §2](./architecture.md#2-기술-스택) 참고) |
| Docker | PostgreSQL·Redis, jOOQ 코드 생성, Testcontainers |
| pnpm, Node.js | `web` |
| `fd`, `rg` | 에이전트 스크립트 |
| `lychee` | 문서 링크 검사 |

## 3. 처음 실행

```bash
git clone <저장소> && cd ticketing
./scripts/setup-hooks.sh            # pre-commit(ktlint, detekt, web 검사) 활성화. 클론 후 1회
sudo ./scripts/setup-firewall.sh      # Linux+UFW: compose 브릿지(br-ticketing)에서 호스트로의 웹훅·수집 허용. 1회
cp .env.example .env                # 로컬 비밀 값. git 제외
docker compose up -d --wait         # PostgreSQL 15432, Redis 16379
./gradlew build                     # 전체 빌드 + 테스트
```

각 앱은 별도 터미널에서 띄운다.

```bash
./gradlew :core:bootRun             # 18080 (시드 데이터: SPRING_PROFILES_ACTIVE=seed ./gradlew :core:bootRun)
./gradlew :realtime:bootRun         # 18081
./gradlew :gateway:bootRun          # 18000. 클라이언트·BFF는 여기를 거친다
cp web/.env.example web/.env.local  # 최초 1회
cd web && pnpm install && pnpm dev  # 13000
```

| 앱 | 포트 | 상태 확인 |
|---|---|---|
| core | 18080 | `curl localhost:18080/actuator/health` |
| realtime | 18081 | `curl localhost:18081/actuator/health` |
| gateway | 18000 | `curl localhost:18000/actuator/health` |
| web | 13000 | 브라우저 |
| fake-pg | 18090 | Compose 상태 |

시드 데이터는 공연장 2곳, 공연 3편, 회차 각 3개이며 다시 실행해도 늘지 않는다. 주최자 ID는 `ticketing.seed.organizer-id`(기본 1)다.

fake-pg(18090)는 컨테이너가 아니라 호스트에서 jar로 뜨며(`./scripts/dev/run.sh --fake-pg`), 웹훅을 `localhost:18080`의 core로 보낸다. `.env`의 `FAKE_PG_WEBHOOK_SECRET`은 core와 fake-pg가 같아야 서명 검증이 통과한다.

## 4. 저장소 지도

| 경로 | 내용 |
|---|---|
| `core/` | 코어 Modulith (`member`, `catalog`, `booking`, `payment`, `settlement`, `notification`) |
| `core-batch/` | Spring Batch 실행 모듈. core 코드 공유 |
| `realtime/` | 대기열·SSE. WebFlux + Coroutines, Redis만 사용 |
| `gateway/` | 라우팅, 입장 토큰·SSE 티켓 검증, rate limit |
| `fake-pg/` | 가짜 PG |
| `web/` | Next.js BFF + 화면 |
| `e2e/`, `scripts/e2e/` | 전체 흐름 E2E |
| `docs/` | 설계서, 로드맵, ADR, 측정 결과 |

## 5. 읽는 순서

1. [AGENTS.md](../AGENTS.md): 작업 방식과 반드시 지킬 설계 규칙 7개
2. [architecture.md](./architecture.md) §1–§4: 구성과 모듈 규칙
3. [flows.md](./architecture/flows.md): 대기열 → 좌석 선점 → 결제 → 확정 흐름
4. [roadmap.md](./roadmap.md): 현재 단계와 다음 미완료 항목
5. 작업 영역별 규칙(해당 경로를 고치기 전에 읽는다): [backend.md](./rules/backend.md), [frontend.md](./rules/frontend.md)
6. 궁금한 결정은 [adr/](./adr/README.md)에서 대안과 근거를 찾는다

## 6. 지켜야 할 규칙 요약

전문은 [AGENTS.md](../AGENTS.md) 4절이다.

- 코어 모듈 간 호출은 Modulith 이벤트로만 한다. 다른 모듈 내부 클래스를 참조하지 않는다.
- 모듈별 스키마를 넘는 외래 키와 조인을 만들지 않는다.
- `realtime`은 PostgreSQL에 접근하지 않는다. 코어는 대기열을 모른다.
- JWT는 브라우저에 노출하지 않는다.
- 좌석 확정의 최종 방어선은 `(회차, 좌석)` 유니크 제약이다.
- 결제 요청과 웹훅은 멱등 키로 중복을 거른다.
- 설계와 다르게 가야 하면 먼저 합의하고 `architecture.md`와 ADR을 같이 고친다.

## 7. 검증 방법

저장소 루트에서 실행한다. Docker가 떠 있어야 한다.

| 목적 | 명령 |
|---|---|
| 테스트 | `./scripts/agent/test/run-tests.sh` (필터: `--tests "패턴"`) |
| 전체 검사 | `./scripts/agent/test/check-summary.sh` |
| Kotlin 포맷·정적 분석 | `./gradlew ktlintFormat` 후 `./gradlew ktlintCheck detekt` |
| web | `./scripts/agent/test/web-check.sh` (`--build`로 빌드 확인) |
| E2E | `./scripts/e2e/run.sh [--web] [--keep] [--tests "패턴"]` |
| Modulith 구조 검증·다이어그램 | `./gradlew :core:modulithDocs` |
| 문서 링크 | `./scripts/agent/docs/check-docs.sh [경로...]` |

- 같은 작업 디렉터리에서 test와 check는 순차 실행한다. 동시에 실행하면 결과 파일이 충돌한다.
- E2E는 `build`·`check`에 포함되지 않는다. core·gateway·realtime 흐름을 바꿨다면 직접 실행한다. 18000·18080·18081·18082(`--web`이면 13000)가 비어 있어야 한다.
- `@Suppress`, baseline 추가, `--no-verify`로 검사를 우회하지 않는다.

## 8. 코드·커밋 규칙

- 대화, 문서, 커밋 메시지는 한국어, 코드 식별자는 영어다.
- 커밋 형식은 `<type>(<scope>): <요약>`이다. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, scope는 모듈명이다.
- 테스트 이름은 한국어 백틱, `Clock` 고정, 코루틴은 `runTest`를 쓴다. 자세한 내용은 [backend.md](./rules/backend.md)에 있다.
- 비밀 값은 저장소에 넣지 않는다. `.env`와 `application-local.yml`을 쓴다.

## 9. 첫 작업 체크리스트

- [ ] 3절대로 스택을 띄우고 세 앱의 `/actuator/health`가 `UP`이다
- [ ] `./scripts/agent/test/run-tests.sh`가 통과한다
- [ ] `./scripts/e2e/run.sh`가 통과한다
- [ ] [roadmap.md](./roadmap.md)에서 현재 단계를 확인했다
- [ ] 작은 변경 하나를 만들고 7절 검사를 통과시켰다

## 개발용 Nginx 설정

`nginx/ticketing.conf.template`은 HTTPS 요청을 web(13000)으로 전달하고 SSE 경로만 Gateway(18000)로 전달한다. Nginx 1.25.1 이상이 필요하며, 기존 Nginx의 `http` 블록에 공통 TLS·프록시 설정과 WebSocket upgrade map이 포함되어 있어야 한다. 인증서와 접근 제어는 사용하는 Nginx 환경에서 관리한다.

`.env`의 `TICKETING_DOMAIN`을 설정하고 다음 스크립트를 실행하면 도메인만 치환해 Nginx sites 디렉터리에 복사한다. Nginx의 `$host` 같은 변수는 치환하지 않는다.

```bash
./scripts/dev/install-nginx.sh
```

복사 후 `nginx -t`로 검사하고 사용하는 환경의 절차에 따라 Nginx를 다시 로드한다. `web/.env.local`에서 `GATEWAY_PUBLIC_URL`과 `WEB_ORIGIN`을 `https://<설정한 도메인>`으로 설정하고 `SESSION_COOKIE_SECURE=true`로 바꾼다. `GATEWAY_URL`은 서버에서 접근하는 로컬 Gateway 주소를 유지한다. 개발 서버는 `WEB_ORIGIN`의 호스트를 HMR 허용 출처로 사용하므로 설정 변경 후 web을 재시작한다.
