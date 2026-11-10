# Step 2: catalog-query-seed

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0~1에서 만든 `core/src/main/kotlin/io/github/ticketing/catalog/` 전체와 `V4__create_catalog_tables.sql`
- `core/src/jooqGenerator/kotlin/io/github/ticketing/codegen/JooqCodegen.kt` (생성 코드의 패키지 확인)
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (공개 경로 추가 대상)
- `docs/rules/backend.md` § 영속성, § API

## 작업

### 1. 조회 API (jOOQ, `catalog.query` 또는 `catalog.repository` 하위)

공개 API이므로 비인증 요청도 허용한다. `SecurityConfig`에 `GET /api/v1/performances/**`와 `GET /api/v1/schedules/**`의 `permitAll`을 추가한다. 이 설정은 member 모듈에 있지만 전역 필터 체인이므로 경로만 추가한다.

| 경로 | 내용 |
|---|---|
| `GET /api/v1/performances?cursor=&size=` | 공연 목록. ID 내림차순 커서 페이지네이션, `size` 기본 20·최대 100. 응답 `{items: [{id, title, venueName, firstStartsAt}], nextCursor}` |
| `GET /api/v1/performances/{id}` | 공연 상세: 공연 정보, 공연장, 등급별 가격, 회차 목록 `[{id, startsAt}]` |
| `GET /api/v1/schedules/{id}/seats` | 회차의 좌석 배치: `[{seatId, section, row, number, grade, price}]`. 좌석 상태(선점, 판매)는 담지 않는다. 상태는 booking과 realtime 소관이다 |

- jOOQ `DSLContext`로 조회하고 조회 전용 DTO에 직접 매핑한다. JPA 엔티티나 JPQL로 조회하지 않는다.
- 생성 코드의 패키지는 `JooqCodegen.kt`에서 확인한다.

### 2. Redis 조회 캐시

- Spring Cache + `RedisCacheManager`. 공연 상세와 회차 좌석 배치를 캐시한다. TTL은 10분이고 직렬화는 JSON이다.
- 공연이나 회차를 등록하면 해당 공연 상세 캐시를 무효화한다(`@CacheEvict` 또는 직접 evict).
- 캐시 이름은 `catalog:` 접두사로 시작한다(예: `catalog:performance`). 이유: Redis를 다른 모듈과 함께 쓴다.
- 캐시 설정 클래스는 catalog 모듈 안에 둔다.

### 3. 시드 러너

- `@Profile("seed")` `ApplicationRunner`(catalog 모듈 안)가 step 1의 **등록 서비스를 호출해** 데이터를 넣는다. 이유: SQL로 직접 넣으면 `ScheduleRegistered`가 발행되지 않아 booking의 좌석 사본이 비게 된다.
- 내용: 공연장 2곳(각 4구역 × 10열 × 20석 정도, 앞 열일수록 높은 등급), 공연 3편, 공연마다 회차 3개
- 주최자 회원 ID는 설정값 `ticketing.seed.organizer-id`(기본 1)로 받는다. 회원 존재 여부는 확인하지 않는다(member 모듈 비참조).
- 같은 이름의 공연장이 이미 있으면 건너뛴다(재실행 안전).
- 실행 방법을 `README.md`에 한 줄로 추가한다. 예: `SPRING_PROFILES_ACTIVE=seed ./gradlew :core:bootRun`

### 4. 테스트 (`core/src/test/kotlin/io/github/ticketing/catalog/`)

- 목록 커서 페이지네이션(다음 페이지, 마지막 페이지의 `nextCursor = null`), 상세, 좌석 배치, 비인증 접근 허용
- 캐시: 같은 상세를 두 번 조회하면 두 번째는 캐시에서 온다. 회차 등록 후에는 갱신된다.
- 시드 러너: `seed` 프로필 테스트에서 실행 후 공연 3편이 있고, 다시 실행해도 늘지 않는다.

### 5. 로드맵

`docs/roadmap.md` M1-2의 "공연 목록·상세 조회 (jOOQ), Redis 조회 캐시"와 "시드 데이터 스크립트"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
rg -q 'Profile\("seed"\)' core/src/main/kotlin/io/github/ticketing/catalog
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.catalog.*"
rg -q '^- \[x\] 공연 목록·상세 조회' docs/roadmap.md
rg -q '^- \[x\] 시드 데이터 스크립트' docs/roadmap.md
./scripts/agent/test/check-summary.sh
```

## 검증 절차

1. catalog 테스트 AC를 먼저 실행해 수정하고, 마지막에 전체 check를 실행한다.
2. `phases/0-catalog/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 조회를 JPA(JPQL, 연관 탐색, `findAll`)로 구현하지 마라. 이유: 쓰기는 JPA, 조회는 jOOQ로 나눈다(ADR 0005).
- 오프셋 페이지네이션을 쓰지 마라. 이유: 목록은 커서 기반이 기본이다.
- 좌석 배치 응답에 선점·판매 상태를 넣지 마라. 이유: booking 소관이고 catalog는 booking 데이터를 조회할 수 없다.
- 시드에서 SQL을 직접 실행하지 마라. 이유: 위 3절
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
