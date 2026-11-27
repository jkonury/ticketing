# Frontend 리뷰 지침 (web)

`web` 변경(diff, PR)을 리뷰할 때 쓰는 기준이다. 작성 규칙은 [frontend.md](frontend.md)가 정의하고, 이 문서는 **그 규칙이 diff에서 지켜졌는지 확인하는 순서와 판정 기준**만 다룬다. 규칙 자체를 여기서 다시 정의하지 않는다. 충돌하면 `frontend.md`가 우선한다.

## 리뷰 방법

1. `git diff --stat`으로 범위를 확인하고 변경 파일을 `server/`, `app/api/`, `features/`, `components/`, `lib/`로 분류한다. 아래 심각도 순서대로 확인하고, 위 단계에서 막히면 아래 단계는 지적만 짧게 남긴다.
2. 이번 변경이 건드리지 않은 기존 코드의 문제는 지적하지 않는다(필요하면 후속 항목으로 제안). Next.js API(캐싱, 쿠키, Route Handler, `proxy`)는 `web/node_modules/next/dist/docs/`의 설치된 버전 문서로 확인하고, 실행 결과(`./scripts/agent/test/web-check.sh`)를 근거로 삼는다. 실패는 실패한 그대로 보고한다.

## 심각도

| 등급 | 의미 | 처리 |
|---|---|---|
| 차단 | 토큰 노출, 인증·CSRF 누락, 설계 규칙 위반, 데이터 정합성 위험 | 머지 전에 반드시 수정 |
| 주의 | 오류 처리 누락, 테스트 부재, 규칙 이탈, 접근성 결함 | 수정 권장. 미루려면 사유 기록 |
| 제안 | 가독성, 중복, 이름 | 선택 |

지적은 `파일:행`, 어긴 규칙(`frontend.md`의 절), 구체적인 실패 시나리오를 함께 적는다. 취향 차이는 차단·주의로 올리지 않는다.

## 1. 차단 항목

- [토큰과 세션](frontend.md#토큰과-세션-최우선-규칙) 위반. 체크 키워드: JWT 비노출(응답·props·`NEXT_PUBLIC_*`·저장소·URL·로그), 쿠키 속성, 갱신 single-flight, 변경 엔드포인트의 `Origin`(CSRF) 검증, 인가를 `proxy.ts`·메뉴 숨김에 맡기지 않음. 서버 전용 경계는 [디렉터리](frontend.md#디렉터리)(`server-only`, `server/env.ts`, `server/auth/`)와 [백엔드 호출](frontend.md#백엔드-호출)의 Gateway 직접 호출·SSE 티켓 규칙
- [AGENTS.md 4절](../../AGENTS.md#4-반드시-지킬-설계-규칙) 설계 규칙: BFF의 백엔드 규칙 재구현·조인 흉내(ADR 0022), `Idempotency-Key` 재사용, 선점 여부를 서버 응답으로 판단

## 2. 주의 항목

- [백엔드 호출](frontend.md#백엔드-호출), [조합](frontend.md#조합-aggregation), [SSE](frontend.md#sse), [컴포넌트](frontend.md#컴포넌트), [예매 화면 규칙](frontend.md#예매-화면-규칙), [테스트](frontend.md#테스트)의 나머지 규칙 이탈
- 리뷰 고유: 이벤트 중복·순서 역전에도 화면 상태가 깨지지 않는가. 변경한 동작에 맞는 테스트가 있는가(삭제·비활성화로 통과시키지 않았는가). 토큰 노출 위험이 있는 변경이면 E2E 토큰 비노출 검증이 유지되는가

## 3. 제안 항목

[TypeScript와 스타일](frontend.md#typescript와-스타일), 접근성, [UI 가이드](../ui-guide.md), 파일 분리·공통화(500줄은 검토 신호일 뿐, 모양만 비슷한 코드를 억지로 합치지 않았는가), "왜"만 적는 주석에 해당하는 사항이다.

## 4. 문서 정합

- 설계와 다르게 구현했다면 `docs/architecture.md` 변경 합의가 있었고 문서와 코드가 어긋나지 않는가. 설계를 바꾸는 결정이면 새 ADR이 있는가.
- 단계 항목을 끝낸 변경이면 해당 로드맵의 체크박스가 갱신됐는가. 보류한 항목을 요청 없이 앞당겨 구현하지 않았는가.
- 실행 명령, 환경 변수, 디렉터리 구조가 바뀌면 `web/README.md`, `.env.example`, [온보딩](../onboarding.md) 등 해당 정보를 담는 문서가 갱신됐는가.

## 5. 검증 확인

- `./scripts/agent/test/web-check.sh`가 통과했는가(라우팅·빌드에 영향이 있으면 `--build`). 사용자가 로컬 웹 E2E를 요청했다면 `./scripts/e2e/run.sh --web`의 결과를 확인했는가. 문서 제목·링크를 바꿨다면 `./scripts/agent/docs/check-docs.sh`를 실행했는가.

## 리뷰 결과 형식

```
## 요약
변경 범위와 전반적 판단 한 줄.

## 차단
- `web/src/...:행` (frontend.md 「절 이름」): 문제와 실패 시나리오. 제안 수정.

## 주의
- ...

## 제안
- ...

## 검증
- web-check: 통과/실패(실패한 테스트 이름과 메시지 일부)
- 실행하지 않은 검사와 사유
```

지적이 없는 등급은 "없음"으로 적는다. 확인하지 못한 항목은 통과로 쓰지 않고 "확인 안 함"으로 적는다.

delegate 스킬로 위임한 리뷰는 [delegate 워크플로 §5](../../.agents/skills/delegate/references/workflow.md#5-지시서-템플릿)의 출력 형식으로 쓰고, 이 문서의 등급은 차단 → 차단 사항, 주의·제안 → 비차단 사항으로 옮긴다.
