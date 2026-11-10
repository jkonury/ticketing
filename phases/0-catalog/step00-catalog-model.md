# Step 0: catalog-model

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/backend.md` § 영속성, § Kotlin (catalog는 계층형 모듈이다)
- `core/src/main/resources/db/migration/V3__create_member_tables.sql` (마이그레이션 작성 형식)
- `core/src/main/kotlin/io/github/ticketing/member/domain/Member.kt`, `member/repository/MemberRepository.kt` (계층형 모듈의 엔티티·리포지토리 형식)
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt` (통합 테스트 베이스)

## 작업

M1-2 catalog의 데이터 모델을 만든다. API는 다음 step에서 만든다.

### 1. Flyway `core/src/main/resources/db/migration/V4__create_catalog_tables.sql`

모든 테이블은 `catalog` 스키마에 만든다. 스키마 안의 FK는 허용하지만, 다른 스키마(`member` 등)로 향하는 FK는 만들지 않는다.

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| `catalog.venue` | `id` identity, `name varchar(100)`, `created_at timestamptz` | |
| `catalog.venue_seat` | `id` identity, `venue_id` FK, `section varchar(20)`, `row_label varchar(10)`, `seat_number int`, `grade varchar(10)` | `UNIQUE (venue_id, section, row_label, seat_number)` |
| `catalog.performance` | `id` identity, `organizer_id bigint`(회원 ID, FK 아님), `title varchar(200)`, `description text`, `venue_id` FK, `created_at` | `organizer_id` 인덱스 |
| `catalog.performance_price` | `performance_id` FK, `grade varchar(10)`, `price numeric(12,0)` | `PK (performance_id, grade)` |
| `catalog.schedule` | `id` identity, `performance_id` FK, `starts_at timestamptz`, `created_at` | `UNIQUE (performance_id, starts_at)` |

- 좌석 등급 `SeatGrade`는 enum으로 `VIP`, `R`, `S`, `A`를 둔다. DB에는 문자열로 저장한다.
- 공연장의 좌석 배치는 공연장에 속한다. 회차는 공연의 공연장 좌석 전체를 판매 대상으로 삼는다. 등급별 수량제 좌석은 의도적으로 미룬 항목이다.

### 2. JPA 엔티티와 리포지토리 (`io.github.ticketing.catalog`)

- `catalog.domain`: `Venue`(애그리거트, `VenueSeat`은 `@OneToMany(LAZY)`로 같은 애그리거트에 둔다), `Performance`(애그리거트, 등급별 가격을 `@ElementCollection` 등으로 포함), `Schedule`(공연 ID로 참조), `SeatGrade`
- `catalog.repository`: `VenueRepository`, `PerformanceRepository`, `ScheduleRepository` (Spring Data JPA)
- 생성은 팩토리 함수나 생성자 하나로 하고 불변식을 검증한다. 좌석은 1개 이상이고 좌석 키는 중복되지 않는다. 가격은 0 이상이고, 공연 가격표에는 공연장 좌석에 쓰인 모든 등급이 들어 있어야 한다. 이 마지막 검증은 등록 서비스(다음 step)가 해도 된다.
- 엔티티는 `data class`가 아니다. `equals`/`hashCode`는 ID 기준이고 setter를 노출하지 않는다.

### 3. 테스트

- `core/src/test/kotlin/io/github/ticketing/catalog/` 아래에 리포지토리 통합 테스트를 둔다(`IntegrationTestSupport` 상속). 저장과 조회, 좌석 유니크 제약 위반을 검증한다.
- 엔티티 불변식은 순수 단위 테스트로 검증한다.

### 4. 로드맵

`docs/roadmap.md` M1-2의 "공연, 회차, 공연장, 좌석 배치 모델"을 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V4__create_catalog_tables.sql
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.catalog.*"
rg -q '^- \[x\] 공연, 회차, 공연장, 좌석 배치 모델' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다. 마이그레이션을 추가했으므로 jOOQ 코드는 `compileKotlin`이 자동으로 다시 생성한다(Docker 필요).
2. `phases/0-catalog/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `member` 스키마로 FK를 만들지 마라. 이유: 모듈별 스키마를 넘는 FK·조인은 금지다(설계 규칙 2). `organizer_id`는 값만 저장한다.
- `member` 모듈의 클래스(`Role`, `Member` 등)를 import하지 마라. 이유: 모듈 간 내부 참조 금지다(설계 규칙 1).
- 기존 마이그레이션 V1~V3를 수정하지 마라. 이유: 적용된 마이그레이션은 새 파일로만 바꾼다.
- 조회용 API나 jOOQ 쿼리를 만들지 마라. 이유: step 1, 2의 범위다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
