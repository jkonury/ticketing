# 화면 캡처

주요 화면을 모바일(390x844)·데스크톱(1440x900)으로 캡처한다. U5에서 `toHaveScreenshot` 시각 회귀 테스트로 바꾼다.

## 전제

- 스택이 떠 있고(`./scripts/dev/run.sh --all`) 시드 데이터가 들어가 있다(`./scripts/dev/seed-demo.sh`).
- 로그인은 BFF CSRF 검사 때문에 `web/.env.local`의 `WEB_ORIGIN` 도메인으로 접속해야 한다. 스크립트가 그 줄을 읽어 baseURL로 쓴다. `CAPTURE_BASE_URL`이 있으면 우선한다.

## 실행

```bash
cd web
pnpm capture                    # mobile + desktop
pnpm capture --project mobile   # 한쪽만
CAPTURE_QUEUE=1 pnpm capture    # 대기 순번(20) 포함
```

대기 순번 캡처는 realtime을 `./scripts/dev/run.sh --all --slow-queue`로 띄웠을 때만 쓴다. 플래그가 없으면 건너뛴다.

예매 흐름은 실행할 때마다 좌석이 팔린다. 선택 가능한 좌석이 2개 이상인 회차를 찾아 쓰므로 반복해도 된다.

## 출력

저장소 루트의 `build/ui-capture/<project>/<NN-name>.png`(git 제외). 콘솔 오류·pageerror가 한 건이라도 있으면 실패한다. 무해한 메시지는 `support.ts`의 `ignoredConsole`에 정규식으로 추가한다.

## CI

PR마다 `.github/workflows/web-e2e.yml`이 웹 E2E 뒤에 시연 데이터를 넣고 캡처를 실행한다. 결과는 실행 페이지의 artifact `ui-capture`에서 받는다.
