# Step 1: architecture-doc

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/design.md` (이 step에서 옮길 원본. 약 12KB라 전체를 읽어도 된다)
- `docs/roadmap.md` § M1-8, § 단계와 무관한 후속 항목
- `docs/architecture.md` (현재 빈 파일, 대상)
- `core/src/test/kotlin/io/github/ticketing/ModularityTests.kt`
- 이벤트와 테이블 확인: `rg --files core/src/main/kotlin | rg '/(booking|payment|catalog)/[A-Z][A-Za-z]+\.kt$'`, `core/src/main/resources/db/migration/V*.sql`

## 작업

로드맵 M1-8을 수행한다.

### 1. `docs/design.md` → `docs/architecture.md` 승격

- `git mv`로 옮긴다. `docs/architecture.md`는 빈 파일이므로 먼저 지운다. 새로 쓰지 않고, **옮기면서 실제 코드 구조에 맞게 고친다.**
- 합의일, 상태, "합의서" 같은 기록성 문구는 지운다. 제목은 "아키텍처"로 바꾼다.
- 12장 "의도적으로 미룬 것"은 `docs/roadmap.md`의 "단계와 무관한 후속 항목" 절로 옮기고, architecture.md에서는 지운다.
- 코드와 다른 서술이 있으면 코드를 기준으로 고친다. 패키지 구조, 이벤트 이름, Redis 키, 테이블 이름을 확인한다.

### 2. 추가할 절

- **예매 흐름 시퀀스 다이어그램**(Mermaid `sequenceDiagram`, 대기열 제외): 사용자 → booking(선점, PENDING) → 결제 요청 → payment → fake-pg → 웹훅 → payment → booking(확정, `sold_seat`, Redis SOLD). 실패 경로는 `alt` 블록으로 넣는다(결제 실패, 웹훅 중복, 만료 후 성공 → 자동 환불, 유니크 위반 → 환불).
- **모듈 의존 다이어그램**: `core/src/test/kotlin/io/github/ticketing/DocumentationTests.kt`에서 Modulith `Documenter`로 생성한다. 출력 폴더는 `docs/modulith/`이고 PlantUML(`.puml`)이다. API는 공식 문서(Spring Modulith 2.1 reference, Documenting Application Modules)로 확인한다. 생성한 `.puml`은 커밋 대상이며, architecture.md에서 링크한다.
- **이벤트 목록**: 표로 정리한다(이벤트, 발행 모듈, 구독 모듈, 페이로드 필드). 실제 코드의 이벤트 클래스와 리스너를 기준으로 한다.
- **테이블 구조(ERD)**: Mermaid `erDiagram`을 스키마별로 그린다(member, catalog, booking, payment). 스키마를 넘는 관계선은 그리지 않는다. 값으로만 참조하는 컬럼은 주석으로 표시한다.

### 3. 참조 수정

`design.md`를 가리키는 모든 참조를 `architecture.md`로 바꾼다. 먼저 `rg -n 'design\.md' --glob '!phases/**' --glob '!.claude/**' .`로 위치를 확인한다.

- 대상: `README.md`, `AGENTS.md`(1절 문서 목록, 3절 작업 방식의 "design.md를 같이 고친다"·"12장의 의도적으로 미룬 것" 문구는 로드맵 절을 가리키게 바꾼다), `docs/rules/backend.md`, `docs/rules/frontend.md`, `docs/adr/README.md`, `docs/glossary.md`, `docs/templates/*.md`, `docs/roadmap.md`
- `AGENTS.md`의 문서 설명 "설계 합의서"는 "아키텍처 문서"로 바꾼다. `CLAUDE.md`는 `@AGENTS.md` import뿐이면 수정하지 않는다.
- `docs/roadmap.md`의 기준 문서 링크와 본문 언급을 고친다. M1-8 항목처럼 승격 작업 자체를 설명하는 완료 항목의 문구는 그대로 두어도 된다.
- ADR 본문의 `../design.md` 링크도 `../architecture.md`로 바꾼다.

### 4. 로드맵

- M1-8의 다섯 항목을 `[x]`로 바꾼다.
- 상단 "상태"를 "M1 완료"로 갱신한다. 단, M1 완료 기준(API 흐름 테스트와 동시성·실패 경로 테스트 통과, architecture.md와 코드 일치)을 확인한 뒤에만 바꾼다.

## Acceptance Criteria

```bash
test ! -e docs/design.md
test -s docs/architecture.md
test -n "$(fd -e puml . docs/modulith)"
! rg -n 'design\.md' README.md AGENTS.md docs --glob '!docs/roadmap.md'
! rg -n '\]\(\./design\.md' docs/roadmap.md
./scripts/agent/docs/check-docs.sh
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.ModularityTests"
```

## 검증 절차

1. `DocumentationTests`를 실행해 `docs/modulith/`를 만든 뒤 위 AC를 실행한다. 링크 오류는 `check-docs.sh` 출력에서 찾아 고친다.
2. `phases/4-m1-verify/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- architecture.md를 처음부터 새로 쓰지 마라. `git mv` 후 수정한다. 이유: 결정의 맥락과 git 히스토리를 보존한다.
- ADR의 결정 내용을 바꾸지 마라. 링크만 고친다. 이유: ADR은 결정 당시의 기록이다.
- `phases/` 아래 파일의 `design.md` 언급은 고치지 마라. 이유: 실행 계획 기록이고 phase 정리 시 삭제된다.
- main 코드를 바꾸지 마라. 문서와 코드가 다르면 문서를 고친다. 코드가 설계 규칙을 어긴 것으로 보이면 `blocked`로 보고한다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
