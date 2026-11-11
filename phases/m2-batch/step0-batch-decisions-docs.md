# Step 0: batch-decisions-docs

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/architecture.md § 4. 코어 모듈 (Modulith)`(모듈 표, 이벤트 목록), `§ 9. Batch`, `§ 10. 외부 연동 > fake-pg 연동 계약`
- `docs/adr/README.md`, `docs/templates/adr.md`
- `docs/roadmap.md § M2. Batch`

큰 문서는 통째로 `Read`하지 말고 `rg -n '^#+ ' docs/architecture.md`로 줄 번호를 찾아 `Read offset/limit`으로 해당 절만 읽는다.

## 작업

M2 구현 전에 합의된 설계 결정을 문서에 반영한다. 코드는 바꾸지 않는다. 테이블 컬럼(ERD)은 구현 중 바뀔 수 있으므로 이 step에서 그리지 않는다(step 10에서 반영).

### 1. ADR 0019 작성: `docs/adr/0019-batch-jobs-in-owning-modules.md`

`docs/templates/adr.md` 형식(상태 / 맥락 / 검토한 대안 / 결정 / 결과)을 따른다. 상태는 `수락`. 담을 결정 세 가지:

1. **Job은 데이터를 소유한 모듈 안에 둔다.** 각 모듈의 `adapter.provided.batch` 패키지에 Job·Step 정의를 두고 `@Profile("batch")`로만 켠다. Batch는 웹 컨트롤러와 같은 입력 어댑터로 본다. `core-batch`는 core 컨텍스트를 웹 서버 없이 `batch` 프로필로 띄우는 런처다.
   - 대안: `core-batch`의 `io.github.ticketing.batch` 패키지에 Job을 두고 모듈이 배치용 유스케이스를 `@NamedInterface`로 공개. 기각 이유: booking·settlement·payment의 내부 포트를 모듈 밖에 공개해야 하고, Job이 여러 모듈 내부를 알게 된다.
   - 결과: core에 Spring Batch 의존이 생긴다. 웹 앱(`core`)에서는 Job 빈이 만들어지지 않고 자동 실행도 꺼 둔다.
2. **결제 대사는 `payment` 모듈이 맡는다.** PG 거래와 `payment.payment`(`pg_transaction_id`, 상태, 금액)를 같은 스키마 안에서 비교한다.
   - 대안: settlement가 결제 이벤트 사본을 쌓아 `orderId`로 맞춤. 기각 이유: 결제 이벤트에 PG 거래 ID가 없어 매칭 정밀도가 낮고, 사본 테이블과 구독이 늘어난다. MSA 분리 시 대사는 결제 서비스와 함께 움직이는 편이 자연스럽다.
3. **정산 원천은 예매 이벤트에 필드를 더해 얻는다.** `BookingConfirmed`, `BookingCancelled`에 `performanceId`, `organizerId`를 추가한다. settlement는 booking 이벤트만 구독한다.
   - 대안: settlement가 `ScheduleRegistered`도 구독해 회차→공연·주최자 사본을 둠. 기각 이유: settlement → catalog 의존이 늘고, settlement 도입 전에 등록된 회차는 사본이 비어 있다.

### 2. `docs/adr/README.md` 목록에 0019 행 추가

### 3. `docs/architecture.md` 수정

- `§ 4` 모듈 표: `payment` 책임에 "결제 대사 (Batch)" 추가, `settlement` 책임은 "정산 (Batch). M2에서 구현"으로.
- `§ 4 > 모듈 규칙`의 "MSA 분리 1순위" 줄은 그대로 둔다.
- `§ 4 > 이벤트 목록`: `BookingConfirmed`, `BookingCancelled` 페이로드에 `performanceId`, `organizerId` 추가. 구독 모듈의 "(settlement 예정)"을 `settlement`로.
- `§ 9. Batch`: 표의 "결제 대사" 행에 소유 모듈이 `payment`임을 적고, 표 아래에 다음 규칙을 목록으로 추가하고 ADR 0019를 링크한다.
  - Job 위치와 실행: 소유 모듈의 `adapter.provided.batch`, `@Profile("batch")`. `core-batch`는 `batch` 프로필 + 웹 없음으로 core를 띄우고 `spring.batch.job.name`으로 Job 하나를 실행한다. 웹 앱에서는 Job 자동 실행을 끈다.
  - 메타데이터: Flyway로 만든 `batch` 스키마(Spring Batch 공식 PostgreSQL DDL).
  - 정산 규칙: 정산일은 이벤트 시각의 Asia/Seoul 날짜. 그날 매출 = 그날 `BookingConfirmed` 금액 합, 환불 = 그날 `refundRequired=true`인 `BookingCancelled` 중 확정 사본이 있는 예매의 금액 합. 지난 날짜의 정산은 다시 계산하지 않는다. 수수료 = (매출 − 환불) × 수수료율(설정, 기본 0.10), 원 미만은 0 방향 절사. 지급액 = 매출 − 환불 − 수수료. `(정산일, 공연)` 단위 upsert.
  - 미결제 정리: 결제 미요청 `PENDING`은 `expiresAt` 경과 시, 결제 요청한 `PENDING`은 `expiresAt + 유예`(설정, 기본 30분) 경과 시 `EXPIRED`. 이후 결제 성공은 기존 자동 환불 경로.
  - 좌석 스냅샷 보정: 시작 전 회차를 대상으로 `sold_seat`와 선점 키를 기준으로 스냅샷을 다시 쓴다.
  - 결제 대사: Asia/Seoul 일자로 PG 거래를 받아 같은 날 요청된 내부 결제와 `orderId`로 맞추고 불일치를 기록만 한다(자동 보정 없음).
  - 재실행: 같은 대상 날짜로 다시 실행해도 결과가 같다(upsert 또는 날짜 단위 삭제 후 재적재).
- `§ 10 > fake-pg 연동 계약`: 거래 조회 `GET /v1/transactions?date=YYYY-MM-DD`(Asia/Seoul 기준 요청 날짜) 한 줄 추가.
- `§ 6.3`의 "일괄 정리는 M2 Batch가 한다" 문장은 유지한다.

### 4. `docs/roadmap.md § M2. Batch`

- "결제 대사 Job" 항목 끝에 "(payment 모듈)"을 붙인다. 체크박스는 건드리지 않는다.

## Acceptance Criteria

```bash
test -f docs/adr/0019-batch-jobs-in-owning-modules.md
rg -q '0019-batch-jobs-in-owning-modules' docs/adr/README.md
rg -q '0019-batch-jobs-in-owning-modules' docs/architecture.md
rg -q 'adapter.provided.batch' docs/architecture.md
! rg -q 'settlement 예정' docs/architecture.md
rg -q '결제 대사 Job.*payment' docs/roadmap.md
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. 위 AC를 실행하고, `check-docs.sh`가 실패하면 링크·앵커를 고친다.

## 금지사항

- ERD(`§ 5 > 테이블 구조`)에 새 테이블을 그리지 마라. 이유: 컬럼은 구현 step에서 정해지며 step 10에서 실제 마이그레이션 기준으로 반영한다.
- 기존 ADR(0001~0018)의 결정 내용을 고치지 마라. 이유: ADR 규칙상 수락된 결정은 새 ADR로만 바꾼다. 0019는 기존 결정을 대체하지 않는 신규 결정이다.
- 코드와 마이그레이션 파일을 건드리지 마라. 이유: 문서 전용 step이다.
- roadmap 체크박스를 체크하지 마라. 이유: 구현이 끝나지 않았다.
