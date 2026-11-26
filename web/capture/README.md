# 화면 캡처

주요 화면을 모바일(390x844)·데스크톱(1440x900)으로 캡처한다. 일부 화면은 `toHaveScreenshot` 시각 회귀 테스트로도 비교한다.

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

## 시각 회귀 비교와 기준 이미지

- 기준 이미지는 CI(ubuntu-latest)에서 만든 것만 커밋한다. 위치는 `web/capture/__screenshots__/<project>/`이다.
- 로컬은 `VISUAL=1`을 지정하지 않으면 비교하지 않는다. 로컬에서 만든 이미지는 폰트·렌더링이 달라 커밋하지 않는다(`VISUAL=1 pnpm capture --update-snapshots`로 만들었다면 지운다).
- CI는 `VISUAL=1`로 캡처하고, 비교가 실패하면 같은 스택에서 새 기준을 만들어 artifact `visual-baseline`으로 올린다(워크플로는 push하지 않는다). 실패 diff는 `web-e2e-logs`의 `web/test-results/`에 있다.

### 기준 갱신 절차

1. 시각이 바뀐 PR에서 CI가 실패한다.
2. `gh run download <run-id> -n visual-baseline -D web/capture/__screenshots__`
3. 바뀐 이미지를 눈으로 확인한다(의도한 변경만 있어야 한다).
4. 이미지를 커밋한다.

### 대상과 제외

- 비교: 03-login, 04-signup, 05-login-error, 11-header-menu(모바일, 시트 요소만), 01-home(머리글 영역만), 13-payment.
- 13-payment는 실행일 기준 일시, 생성형 포스터, 누적 좌석·쿠폰, 결제 카운트다운을 `mask`로 가리고 레이아웃만 본다. 포스터는 `data-visual-mask` 속성으로 찾는다.
- 02-performance는 비교에서 뺐다. 마스크로 가리면 남는 것이 가격표뿐인데 CI에서 비교 실행과 기준 생성 실행의 페이지 높이가 달라지는 현상(원인 미확인)이 있었다. 캡처 이미지로 눈으로 확인한다. 상세 화면의 시각 회귀가 필요하면 고정 데이터를 쓰는 별도 시드를 먼저 정한다.
- 01-home은 공연 수·순서·제목 줄 수가 실행마다 달라 카드 영역을 비교하지 않고 머리글(제목·설명)만 `target`으로 비교한다. 11-header-menu도 시트 뒤 배경이 목록이라 시트 요소만 비교한다. 화면 길이가 가변인 화면은 `fullPage: false`나 `target`으로 좁힌다.
- 제외: 마이페이지·쿠폰·예매 완료, 주최자·관리자, 좌석 상태(12-seats), 대기열. 예매·좌석이 반복할수록 쌓이거나 시간·계정에 따라 달라서다.
