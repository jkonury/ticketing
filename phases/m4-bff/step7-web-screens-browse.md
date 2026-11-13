# Step 7: web-screens-browse

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/frontend.md § 컴포넌트`, `§ TypeScript와 스타일`, `§ 테스트`
- `web/src/server/aggregate/` (step 6 결과: 조합 함수와 반환 타입), `web/src/server/api/catalog.ts`
- `web/src/server/auth/require-user.ts`, `web/src/server/auth/current-user.ts`, `web/src/server/auth/csrf.ts`
- `web/src/app/layout.tsx` (step 5의 헤더)

## 작업

예매 흐름을 제외한 조회 화면과 주최자 등록 화면을 만든다. 데이터는 Server Component에서 조합 함수로 가져온다. 모든 라우트에 `loading.tsx`와 `error.tsx`를 둔다(`error.tsx`는 `'use client'`).

### 1. 공연 목록 `app/page.tsx`

- `GET /performances` 첫 페이지, "더 보기"는 `?cursor=` 링크(클라이언트 상태 없이). 카드: 제목, 공연장, 첫 회차 시각(Asia/Seoul로 표시).

### 2. 공연 상세 `app/performances/[id]/page.tsx`

- `getPerformanceDetail`. 404면 `notFound()`.
- 공연 정보, 등급별 가격, 회차 목록(시각, 잔여 좌석 `n / 전체` 또는 "잔여 좌석 확인 불가").
- 로그인 사용자의 이 공연 예매가 있으면 "예매 내역 있음"과 상태 표시. 조회 실패면 "예매 내역을 불러오지 못했습니다".
- 회차별 "예매하기" 링크 → `/booking/{scheduleId}`. 잔여 좌석 0이면 비활성.

### 3. 마이페이지 `app/my/page.tsx`

- `requireUser` 후 `getMyPage`. 예매 상태와 결제 상태를 한 행에 표시. 결제 상태 섹션이 `unavailable`이면 열 대신 안내 문구.
- 상태 라벨 매핑은 `lib/labels.ts` 한 곳에 둔다(예매: PENDING/CONFIRMED/EXPIRED/CANCELLED, 결제: REQUESTED/SUCCEEDED/FAILED/REFUND_REQUESTED/REFUNDED).
- PENDING·CONFIRMED 예매에 취소 버튼(클라이언트 컴포넌트, `POST /api/bookings/[id]/cancel` 호출 후 `router.refresh()`). 요청 중 비활성.

### 4. 주최자 대시보드 `app/organizer/page.tsx`

- ORGANIZER가 아니면 403 안내(메뉴 숨김은 편의일 뿐이며 백엔드 403도 같은 안내로 처리).
- 기간은 `?from&to`(기본: 오늘 포함 최근 7일, Asia/Seoul). 판매 현황 표(공연·회차별 판매 좌석/전체, 확정 금액, 공연별 합계)와 정산 표. 각각 `unavailable`이면 그 표 자리에 안내.

### 5. 주최자 등록 `app/organizer/new/page.tsx`

- 한 폼, Server Action 하나(`features/organizer/actions.ts`, 첫 줄 CSRF 검사)로 순서대로 호출: 공연장 → 공연 → 회차.
  - 공연장: 이름, 구역 목록(쉼표 구분, 예 `A,B`), 열 수, 열당 좌석 수, 등급별 열 수(VIP·R·S·A 순서로 앞 열부터 배정, 합이 열 수와 같아야 함). 좌석 배열은 서버에서 만든다(최대 5000석).
  - 공연: 제목, 설명, 사용한 등급의 가격.
  - 회차: 시작 시각(`datetime-local`, Asia/Seoul로 해석해 ISO Instant로 변환). 여러 개 입력 가능하면 좋지만 1개로 충분하다.
- 중간 단계가 실패하면 어디까지 생성됐는지(공연장 ID 등)와 오류 코드를 보여 준다. 성공 시 `/performances/{id}`로 이동.
- 입력 검증은 zod로 서버에서 한다.

### 6. 헤더 내비게이션

공연 목록, 마이페이지(USER), 주최자 대시보드·공연 등록(ORGANIZER), 로그인/로그아웃.

### 7. 테스트 (Testing Library)

- 공연 상세 표시 컴포넌트: 잔여 좌석 `unavailable` 안내, 0석이면 예매 비활성.
- 마이페이지 목록: 결제 상태 `unavailable` 안내.
- 주최자 등록: 좌석 배열 생성 함수(순수 함수로 분리) — 등급 배정, 합 불일치 오류, 5000석 초과 오류.
- 시각 변환 유틸: `datetime-local` → Asia/Seoul Instant.

## Acceptance Criteria

```bash
for r in performances/\[id\] my organizer organizer/new; do test -f "web/src/app/$r/page.tsx" && test -f "web/src/app/$r/loading.tsx" && test -f "web/src/app/$r/error.tsx" || exit 1; done
test -f web/src/lib/labels.ts
! rg -n 'useEffect' web/src/app web/src/features/performance web/src/features/my 2>/dev/null
! rg -n 'accessToken|refreshToken' web/src/app web/src/features web/src/components
./scripts/agent/test/web-check.sh --build
```

## 검증 절차

1. 컴포넌트·유틸 테스트 → `web-check.sh --build`.
2. 가능하면 로컬 스택 + `core`를 `seed` 프로필로 띄워(`./gradlew :core:bootRun --args='--spring.profiles.active=local,seed'` 등 실제 프로필 구성은 `core/src/main/resources`에서 확인) 목록·상세·주최자 등록을 브라우저로 확인한다. 못 했으면 보고한다.

## 금지사항

- 클라이언트 컴포넌트에서 `useEffect`로 초기 데이터를 가져오지 마라. 이유: `docs/rules/frontend.md § 컴포넌트`.
- 서버 데이터를 전역 클라이언트 스토어에 복사하지 마라. 이유: 같은 문서.
- 메뉴 숨김을 인가로 취급하지 마라. 이유: 인가는 백엔드가 강제한다. 백엔드 403을 그대로 반영한다.
- 대기열·좌석·결제 화면을 만들지 마라. 이유: step 8의 범위다. 상세 화면의 링크 대상 경로만 맞춘다.
