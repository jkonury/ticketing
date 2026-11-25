---
name: loadtest-record
description: k6(scripts/loadtest/run.sh)나 Gatling으로 부하 측정을 실행하고 결과를 docs/performance/<단계>/NN-*.md로 기록할 때 쓰는 절차. 비교 기준 선정, 회차별 확인, 기록 형식, 로드맵 반영을 다룬다.
---

# 부하 측정과 기록 절차

실행 방법·지표 해석·문제 해결은 [부하 테스트 런북](../../../docs/performance/loadtest-runbook.md)이 원본이다. 이 문서는 측정 한 건의 순서와 기록 형식만 정한다.

## 1. 실행 전

- 비교 기준 문서를 하나 정한다(예: `docs/performance/m6/01-pre-split-baseline-gatling.md`). 코드·관측·서버 설정이 기준과 다르면 전후 비교가 아니라 별도 측정으로 기록한다([런북 먼저 확인](../../../docs/performance/loadtest-runbook.md#먼저-확인-이번-결과를-무엇과-비교할-것인가)).
- 측정은 다른 Gradle test·check·E2E와 동시에 실행하지 않는다. 작업 트리의 dirty 상태는 `env.txt`에 남으므로 의도한 변경만 있는지 `git status`로 확인한다.

## 2. 실행과 회차 확인

- k6: `./scripts/loadtest/run.sh --scenario <smoke|queue-surge|hot-seats|seat-conflict-refund|coupon-drop>`. smoke를 먼저 통과시킨다.
- Gatling: [loadtest/gatling/README.md](../../../loadtest/gatling/README.md).
- 회차마다 [런북 6절](../../../docs/performance/loadtest-runbook.md#6-매-회차-결과-확인)의 정합성·중단 건수·표본 수를 확인한다. 실패·중단 회차는 지우지 않고 경로와 사유를 기록한다.

## 3. 기록

`docs/performance/<단계>/`에 다음 번호로 새 파일을 만든다(`ls docs/performance/<단계>`). 절 구성:

1. `# <단계> <작업> 측정` 다음 줄에 비교 기준 링크와 원본 `build/loadtest/runs/...` 경로
2. `## 조건`: 일시(KST), 커밋과 dirty 여부, CPU·힙 배정, 예열·AOT·관측·Scouter·rate limit 여부, 기준과 다른 점
3. `## 결과`: 시나리오별 회차 표(p95, 오류율, SSE 성공률, 표본 수)와 기준 대비 차이
4. `## 해석과 한계`: 기준 미달은 그대로 적는다. 표본 없음(`null`, p95 0)을 0으로 해석하지 않는다

- 로드맵 항목과 관련된 측정이면 `docs/roadmap.md`의 해당 줄에 문서 링크를 단다.
- 마지막에 `./scripts/agent/docs/check-docs.sh docs/performance`를 실행한다.
