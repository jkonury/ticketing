# Step 1: fake-pg-compose

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `compose.yaml`, `.env.example`
- `fake-pg/src/main/resources/application.yml` (step 0에서 정한 설정 키: `FAKE_PG_WEBHOOK_URL`, `FAKE_PG_WEBHOOK_SECRET`, 포트 8090)
- `README.md`의 로컬 실행 절

## 작업

### 1. `fake-pg/Dockerfile`

- 베이스 이미지는 `eclipse-temurin:25-jre`(정확한 태그를 Docker Hub에서 확인한다)
- 미리 빌드한 bootJar(`fake-pg/build/libs/*.jar`, plain jar 제외)를 복사해 실행한다. 이미지 안에서 Gradle을 실행하지 않는다. 이유: 빌드가 느리고 Gradle 캐시를 다시 받는다.
- root가 아닌 사용자로 실행하고 `EXPOSE 8090`
- bootJar 이름을 고정해야 하면 `fake-pg/build.gradle.kts`에서 `archiveFileName`을 지정한다.

### 2. `compose.yaml`에 `fake-pg` 서비스 추가

- `build: { context: ./fake-pg }`, 포트는 `127.0.0.1:8090:8090`
- 환경 변수 `FAKE_PG_WEBHOOK_SECRET: ${FAKE_PG_WEBHOOK_SECRET}`, `FAKE_PG_WEBHOOK_URL: ${FAKE_PG_WEBHOOK_URL:-http://host.docker.internal:8080/api/v1/payments/webhooks/fake-pg}`
- 코어는 호스트에서 `bootRun`으로 뜨므로 컨테이너가 호스트로 웹훅을 보내야 한다. Linux에서도 동작하도록 `extra_hosts: ["host.docker.internal:host-gateway"]`를 넣는다.
- healthcheck는 이미지에 curl이 없으면 생략하거나, Actuator 없이 가능한 방법을 쓴다. Actuator를 추가할 필요는 없다.
- 첫 줄 주석에 "먼저 `./gradlew :fake-pg:bootJar` 필요"를 적는다.

### 3. `.env.example`

`FAKE_PG_WEBHOOK_SECRET=change-me-to-a-random-string`을 추가한다. 코어도 같은 값을 쓴다는 주석을 한 줄 붙인다.

### 4. 문서

- `README.md`의 로컬 실행 절에 bootJar 빌드 후 `docker compose up -d`를 한다고 적는다.
- `AGENTS.md` 6절 명령 블록의 `docker compose up -d` 주석은 이미 "PostgreSQL, Redis, fake-pg"이므로 그대로 둔다. 실제와 다르면 고친다.
- `docs/roadmap.md` M1-4의 "Docker Compose에 추가"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -f fake-pg/Dockerfile
rg -q '^  fake-pg:' compose.yaml
rg -q '^FAKE_PG_WEBHOOK_SECRET=' .env.example
docker compose --env-file .env.example config -q
rg -q '^- \[x\] Docker Compose에 추가' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행한다. 가능하면 `./gradlew :fake-pg:bootJar -q && docker compose --env-file .env.example build fake-pg`로 이미지 빌드도 확인한다. 이 확인은 AC가 아니며, 실패하면 원인을 summary에 적는다.
2. `phases/1-fake-pg/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `.env` 파일을 만들거나 수정하지 마라. 이유: git 제외 대상인 사용자 로컬 파일이다.
- 기존 `postgres`, `redis` 서비스 설정을 바꾸지 마라.
- fake-pg 소스 코드를 바꾸지 마라. 이유: step 0에서 끝났다. Dockerfile에 필요한 빌드 설정(`archiveFileName`)만 예외다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
