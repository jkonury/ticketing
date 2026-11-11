# Step 6: docs-finalize

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m3-1-gateway/README.md` (합의된 결정)
- `gateway/src/main/resources/application.yml`, `gateway/src/main/kotlin/io/github/ticketing/gateway/` (step 2~5 결과. 설정 키·기본값·라우트 확인용)
- `docs/architecture.md § 5. 데이터 > Redis 용도`, `§ 6.1 대기열과 입장`, `§ 7. 인증·인가` (step 0에서 갱신됨)
- `docs/roadmap.md § M3-1. gateway`

큰 문서는 통째로 `Read`하지 말고 `rg -n '^#+ ' docs/architecture.md`로 줄 번호를 찾아 `Read offset/limit`으로 해당 절만 읽는다.

## 작업

구현 결과와 문서를 맞추고 로드맵을 갱신한다. 코드는 바꾸지 않는다.

### 1. `docs/architecture.md`

- `§ 6.1`의 Gateway 규칙 목록(step 0에서 추가)을 실제 구현과 대조한다. 라우트 ID, 오류 코드, 본문 크기 상한, CORS 허용 경로가 다르면 구현 기준으로 고친다.
- rate limit 항목에 한도 설정 이름(`default`, `booking-create`, `streams`)과 기본값, 키(`member:<id>` / `ip:<주소>`), `X-Forwarded-For` 신뢰 홉 수, Redis 오류 시 동작(구현에서 확인한 라이브러리 기본 동작)을 적는다.
- `§ 3 > 배포 단위`의 `gateway` 행이 실제 역할(라우팅, JWT 검증, 입장 토큰·SSE 티켓 검증, rate limit)과 맞는지 확인한다.
- 남은 과제를 한 줄로 적는다: 코어가 Gateway를 거치지 않고 직접 노출되지 않도록 하는 네트워크 구성은 M6(k8s)에서 한다(ADR 0008의 결과).

### 2. `docs/roadmap.md § M3-1. gateway`

- 네 항목(라우팅, 입장 토큰 검증 필터, SSE 티켓 검증 필터, rate limit)을 체크한다.
- 상단 "상태" 줄을 "M3-1 완료, M3-2 대기"로 고친다.

### 3. `AGENTS.md § 6. 명령`

- 코드 블록에 `./gradlew :gateway:bootRun`을 추가하고, BFF·클라이언트는 Gateway(8000)를 거쳐 호출한다는 주석을 붙인다.

## Acceptance Criteria

```bash
rg -q '\- \[x\] 코어·실시간 앱 라우팅' docs/roadmap.md
rg -q '\- \[x\] rate limit' docs/roadmap.md
rg -q 'booking-create' docs/architecture.md
rg -q 'gateway:bootRun' AGENTS.md
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. 위 AC를 실행하고, `check-docs.sh`가 실패하면 링크·앵커·백틱 경로를 고친다.

## 금지사항

- M3-2·M3-3 항목을 체크하지 마라. 이유: realtime 구현과 통합 검증이 남아 있다.
- 기존 ADR(0001~0020)을 고치지 마라. 구현이 ADR 0020과 달라졌다면 문서를 맞추지 말고 사용자에게 보고한다. 이유: 결정 변경은 새 ADR로만 한다.
- 코드와 설정 파일을 건드리지 마라. 이유: 문서 전용 step이다.
