# Step 10: docs-finalize

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m4-bff/README.md` (합의된 결정)
- `web/package.json`, `web/.env.example`, `scripts/e2e/run.sh` (실제 스크립트·환경 변수·옵션 확인용)
- `docs/architecture/integrations.md § 8. BFF (Next.js)` (step 0에서 갱신됨)
- `docs/architecture.md § 2. 기술 스택 > 확정 버전`, `§ 11. 개발 환경과 테스트`
- `docs/roadmap.md § M4. Next.js BFF`
- `docs/rules/frontend.md`

## 작업

구현 결과와 문서를 맞추고 로드맵을 갱신한다. 코드는 바꾸지 않는다.

### 1. `docs/architecture.md`

- `§ 2 > 확정 버전` 표에 Next.js `16.4.6`, Node `24` 행을 추가한다.
- `§ 11`의 E2E 항목에 `./scripts/e2e/run.sh --web`(Playwright, 전체 예매 시나리오 + JWT 비노출 검사)을 덧붙인다.

### 2. `docs/architecture/integrations.md § 8`

step 0에서 적은 내용(세션 키, 갱신 규칙, CSRF, BFF Route Handler 목록)을 실제 구현과 대조해 다르면 구현 기준으로 고친다. BFF Route Handler 경로 표가 없으면 `web/src/app/api/` 기준으로 추가한다.

### 3. `docs/rules/frontend.md`

`§ 테스트` 끝에 실행 명령 두 줄을 추가한다: `./scripts/agent/test/web-check.sh [--build]`, `./scripts/e2e/run.sh --web`.

### 4. `docs/roadmap.md § M4`

- 열 항목을 모두 체크한다. 상단 "상태" 줄을 "M0·M1·M2·M3·M4 완료, M5 대기"로 고친다.

### 5. `AGENTS.md`

- `§ 6. 명령` 코드 블록의 `cd web && pnpm install && pnpm dev`(step 4에서 바뀜) 위에 `cp web/.env.example web/.env.local`(최초 1회) 주석 줄을 추가한다.
- `§ 7`의 E2E 항목에 `--web`(Playwright, 포트 3000 추가) 설명을 덧붙인다.

## Acceptance Criteria

```bash
! rg -q '^- \[ \]' <(sed -n '/^## M4/,/^## M5/p' docs/roadmap.md)
rg -q '16.4.6' docs/architecture.md
rg -q 'run.sh --web' docs/architecture.md AGENTS.md docs/rules/frontend.md
rg -q 'web-check' docs/rules/frontend.md
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. 위 AC를 실행하고, `check-docs.sh`가 실패하면 링크·앵커·백틱 경로를 고친다.

## 금지사항

- step 9의 `run.sh --web`이 통과하지 않았다면 로드맵을 체크하지 말고 멈춰서 보고한다. 이유: 완료 기준 미충족.
- 기존 ADR(0001~0022)의 결정 본문을 고치지 마라. 구현이 ADR 0022와 달라졌다면 문서를 맞추지 말고 보고한다. 이유: 결정 변경은 새 ADR로만 한다.
- M5 항목을 건드리지 마라.
- 코드와 설정 파일을 건드리지 마라. 이유: 문서 전용 step이다.
