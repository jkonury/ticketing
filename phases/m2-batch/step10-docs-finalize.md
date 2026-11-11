# Step 10: docs-finalize

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- 마이그레이션: `core/src/main/resources/db/migration/V7__*.sql` ~ `V10__*.sql` (step 1, 4, 5, 9)
- `docs/architecture.md § 5. 데이터 > 테이블 구조 (ERD)`, `§ 9. Batch` (step 0·1·5에서 갱신됨. 줄 번호가 바뀌었으니 `rg -n '^#+ ' docs/architecture.md`로 찾는다)
- `docs/roadmap.md § M2. Batch`
- `docs/modulith/*.puml`

## 작업

M2 구현 결과를 문서에 맞춘다. 코드는 바꾸지 않는다.

1. `docs/architecture.md § 5 > 테이블 구조 (ERD)`
   - 서두 문장(원본 마이그레이션, `public.event_publication`)에 `batch` 스키마(Spring Batch 메타데이터, 공식 DDL)가 있다는 한 줄을 더한다. ERD는 그리지 않는다.
   - `#### settlement` 절을 새로 만들고 `booking_sale`, `booking_refund`, `daily_settlement`를 기존 mermaid `erDiagram` 형식으로 그린다. 컬럼은 V8·V9 마이그레이션 그대로. 스키마 밖 값 참조는 기존처럼 따옴표 주석(`"booking 값 참조"`)으로 표시한다.
   - `#### payment` 절에 `pg_transaction_snapshot`, `reconciliation_mismatch`를 V10 그대로 추가한다.
   - `§ 5 > PostgreSQL`의 스키마 목록에 `batch`를 더한다.
2. `docs/architecture.md § 9. Batch`: Job 이름 4개(`dailySettlementJob`, `expirePendingBookingsJob`, `reconcileSeatSnapshotJob`, `paymentReconciliationJob`)와 소유 모듈, 파라미터를 표로 정리한다. 이미 적힌 규칙과 겹치면 표로 합치고 중복 문장은 지운다.
3. `docs/architecture.md § 4 > 모듈 의존 다이어그램`의 의존 방향 문장에 `settlement → booking`(예매 이벤트 사본)을 더한다.
4. `docs/roadmap.md`
   - M2의 모든 체크박스를 `[x]`로 바꾼다. 단, 구현되지 않은 항목이 있으면 체크하지 말고 완료 보고에 이유를 적는다(코드에서 Job 이름을 `rg`로 확인).
   - 상단 `상태:` 줄을 "M0·M1·M2 완료 …, M3 대기"로 고친다.
5. `DocumentationTests`를 실행해 `docs/modulith/*.puml`이 최신인지 확인한다.

## Acceptance Criteria

```bash
rg -q 'booking_sale' docs/architecture.md
rg -q 'reconciliation_mismatch' docs/architecture.md
rg -q 'paymentReconciliationJob' docs/architecture.md
! awk '/^## M2\./,/^## M3\./' docs/roadmap.md | rg -q '\[ \]'
./scripts/agent/test/run-tests.sh :core:test --tests "*DocumentationTests"
./scripts/agent/docs/check-docs.sh
```

## 검증 절차

1. AC를 실행한다.
2. **M2 완료 기준 확인**: Docker(`docker compose up -d`)가 떠 있으면 네 Job을 같은 날짜로 각각 두 번 실행하고 두 번째 실행 뒤 결과 행 수가 같은지 확인한다. 실행 형식은 `docs/architecture.md § 9. Batch`의 실행 줄을 따른다. 실행하지 못했으면 그 사실과 이유를 완료 보고에 적는다.

## 금지사항

- 코드·마이그레이션을 수정하지 마라. 이유: 문서 동기화 step이다. 문서와 코드가 다르면 문서를 코드에 맞추고, 코드가 설계와 어긋나 보이면 고치지 말고 완료 보고에 적는다.
- ERD 컬럼을 마이그레이션과 다르게 적지 마라. 이유: ERD의 원본은 마이그레이션이다.
- "단계와 무관한 후속 항목" 표를 바꾸지 마라. 이유: 이번 phase 범위가 아니다.
