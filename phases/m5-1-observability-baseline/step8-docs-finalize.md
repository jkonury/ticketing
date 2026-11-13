# Step 8: docs-finalize

- model: haiku / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `phases/m5-1-observability-baseline/README.md` (합의된 결정)
- `docs/performance/m5-baseline.md` (step 7 결과. 상단 요약과 병목 후보 절만)
- `scripts/loadtest/run.sh`의 사용법 주석, `compose.observability.yaml`의 포트
- `docs/roadmap.md § M5. 부하 테스트와 관측`
- `docs/architecture.md § 11. 개발 환경과 테스트` (step 0에서 갱신됨. `rg -n '^## 11' docs/architecture.md`로 찾는다)
- `docs/runbook.md` (`rg -n '^#+ ' docs/runbook.md`로 절 목록만 먼저 본다)

## 작업

구현 결과와 문서를 맞춘다. 코드는 바꾸지 않는다.

### 1. `docs/roadmap.md § M5`

- `Micrometer + Prometheus + Grafana, 분산 추적`, `k6 시나리오: 1만 명 동시 진입, 인기 좌석 집중 선점`을 체크한다.
- `기준 수치 측정 후 병목 개선, 전후 수치 기록`, `Virtual Thread 관련 점검`, `결과를 docs/에 문서화`는 체크하지 않는다(M5-2에서 개선 후 완료). 대신 각 항목 아래에 "기준 측정 완료: [m5-baseline.md](./performance/m5-baseline.md)" 한 줄을 덧붙인다.
- 상단 "상태" 줄을 "M5-1(관측·기준 측정) 완료, M5-2(개선) 대기"로 고친다.
- "단계와 무관한 후속 항목" 표의 `전체 관측 스택` 행을 지운다(이제 도입됨).

### 2. `docs/architecture.md § 11`

- 관측·부하 테스트 항목이 실제 구현(compose 파일 이름, Grafana 3001, 스크립트 경로, 시나리오 이름, `TRACING_EXPORT_ENABLED`·`TRACING_SAMPLING_PROBABILITY`)과 맞는지 대조해 고친다.
- 기준 측정 결과 문서 `docs/performance/m5-baseline.md`를 링크한다.

### 3. `docs/runbook.md`

"관측 스택 띄우기와 부하 테스트 실행" 절을 추가한다: 관측 스택 기동·종료 명령, Grafana·Prometheus·Tempo 주소, 앱의 span 내보내기를 켜는 환경 변수, `run.sh` 주요 옵션 예시, 정합성 검증 실패 시 확인할 것(실행 디렉터리의 `integrity-*.json`, core 로그).

### 4. `AGENTS.md § 6. 명령`, `§ 7. 테스트 실행`

- § 6에 관측 스택 기동(`docker compose -f compose.yaml -f compose.observability.yaml up -d`)과 `./scripts/loadtest/run.sh --scenario <smoke|queue-surge|hot-seats>`를 추가한다.
- § 7에 한 줄 추가: 부하 테스트는 `build`·`check`에 포함되지 않으며, 다른 Gradle 작업과 동시에 실행하지 않는다. 결과는 `build/loadtest/runs/`에 저장한다.

## Acceptance Criteria

```bash
rg -q 'M5-1' docs/roadmap.md
rg -q 'm5-baseline.md' docs/roadmap.md
! rg -q '전체 관측 스택' docs/roadmap.md
rg -q 'compose.observability.yaml' AGENTS.md
rg -q 'scripts/loadtest/run.sh' docs/runbook.md
./scripts/agent/docs/check-docs.sh docs/roadmap.md docs/architecture.md docs/runbook.md AGENTS.md
```

## 검증 절차

1. 위 AC를 실행한다.

## 금지사항

- M5의 "병목 개선", "Virtual Thread 점검", "문서화" 항목을 체크하지 마라. 이유: 완료 기준(개선 전후 수치)은 M5-2에서 채운다.
- `AGENTS.md`의 다른 절을 고치지 마라. 이유: 모든 세션에 로드되는 파일이라 변경을 최소로 한다.
- 측정 수치를 roadmap·architecture에 옮겨 적지 마라. 이유: 수치는 `docs/performance/`에만 두고 링크로 참조한다(문서 간 불일치 방지).
