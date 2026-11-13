# Step 0: loadtest-decisions-docs

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m5-1-observability-baseline/README.md` (합의된 결정. ADR의 결정 내용은 여기서 옮긴다)
- `docs/templates/adr.md` (ADR 형식)
- `docs/adr/0023-schedule-detail-api.md` (머리말 형식 예시. 앞 15줄만 본다)
- `docs/adr/README.md` (목록 표)
- `docs/architecture.md § 11. 개발 환경과 테스트` (`rg -n '^## 11' docs/architecture.md`로 찾아 해당 절만 읽는다)

## 작업

M5 관측·부하 테스트 방식을 ADR로 남기고 아키텍처 문서에 결론을 반영한다. 코드는 바꾸지 않는다.

### 1. `docs/adr/0024-observability-and-load-test.md` (새 파일)

`docs/templates/adr.md` 형식을 따른다. 상태 `수락`, 작성일·합의일 2026-11-12, 결정 주체 사용자.

검토한 대안과 선택 이유를 다음 결정마다 적는다.

| 결정 | 검토한 대안 | 선택 이유 |
|---|---|---|
| 추적: Micrometer Tracing + OTel 브리지 → OTLP → Tempo | Brave + Zipkin, OTel + Jaeger | Grafana 하나에서 지표(exemplar)와 trace를 연결한다. OTLP 표준이라 M6 k8s에서도 그대로 쓴다 |
| 부하 경로: k6 → Gateway 직접 | web BFF 경유 | BFF를 거치면 1만 개 세션 로그인과 Next 서버가 측정에 섞여 원인 분석이 어려워진다. 측정 대상은 대기열·realtime·core다 |
| 인증: k6가 테스트 전용으로 JWT를 직접 서명 | 실제 로그인 API 1만 회 | bcrypt 로그인 1만 회가 준비 시간과 부하를 차지한다. 로컬 `JWT_SECRET`은 부하 테스트 환경에서만 쓴다 |
| SSE: xk6-sse 커스텀 빌드 | 상태 조회 폴링, 일부만 SSE | 1만 개 SSE 동시 연결 유지가 WebFlux realtime 검증의 핵심이다. 폴링은 rate limit(초당 20)과도 겹친다 |
| phase 분할: M5-1(관측·기준 측정), M5-2(개선) | 한 phase | 개선 내용은 기준 측정 결과를 봐야 정할 수 있다 |
| 실행 환경: 호스트 `java -jar` + `taskset` | 컨테이너 CPU 제한 | 컨테이너 이미지는 M6 범위다. CPU 분리로 k6와 앱의 경합을 줄이고 하드웨어를 문서에 남긴다 |

결과 절에 적을 것: 측정 수치는 단일 머신 기준이라 절대값보다 개선 전후 비교에 의미가 있다. 테스트 전용 JWT 서명은 운영 경로가 아니다. 로그 수집(Loki)과 web 계측은 하지 않는다.

### 2. `docs/adr/README.md`

목록 표 끝에 `| [0024](0024-observability-and-load-test.md) | 관측 스택과 부하 테스트 방식 | 수락 |`를 추가한다.

### 3. `docs/architecture.md § 11`

- "부하 테스트" 항목을 다음 내용으로 바꾼다. k6(xk6-sse 빌드)가 Gateway를 직접 호출한다. 시나리오는 `queue-surge`(1만 명 대기열 → 예매 전체 흐름)와 `hot-seats`(1,000명이 100석 동시 선점)이다. 실행은 `scripts/loadtest/`, 결과는 `docs/performance/`에 둔다. ADR 0024를 링크한다.
- "관측" 항목을 다음 내용으로 바꾼다. Micrometer + Prometheus 지표, Micrometer Tracing(OTel) → Tempo, Grafana. `compose.observability.yaml`로 띄운다. ADR 0024를 링크한다.
- `docs/architecture.md`의 문서 구성 표나 ADR 목록에 ADR 번호가 나열되어 있으면 0024를 추가한다(`rg -n '0023' docs/architecture.md`로 확인).

## Acceptance Criteria

```bash
test -f docs/adr/0024-observability-and-load-test.md
rg -q '0024-observability-and-load-test' docs/adr/README.md
rg -q 'compose.observability.yaml' docs/architecture.md
rg -q 'hot-seats' docs/architecture.md
./scripts/agent/docs/check-docs.sh docs/adr docs/architecture.md
```

## 검증 절차

1. 위 AC를 실행한다.

## 금지사항

- 아직 없는 파일(`compose.observability.yaml`, `scripts/loadtest/`, `docs/performance/`)로 마크다운 링크를 걸지 마라. 이유: 링크 검사(lychee)가 실패한다. 백틱 경로로만 적는다. `check-doc-refs.sh`가 백틱 경로를 검사해 실패하면 "(M5-1에서 추가)"처럼 경로가 아닌 표현으로 바꾼다.
- `docs/roadmap.md`의 체크박스를 바꾸지 마라. 이유: 구현이 끝난 step 8에서 갱신한다.
- 기존 ADR 본문을 고치지 마라. 이유: 수락된 ADR은 고치지 않는다(`docs/adr/README.md` 작성 규칙).
