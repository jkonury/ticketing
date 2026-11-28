# 워크플로 지표 집계

계획 단위로 묶은 파이프라인 지표 집계를 둔다([ADR 0048](../../adr/0048-ai-dev-workflow-pipeline.md) 결정 8). 계획별 집계는 아래 "W4 집계 리포트"를 본다.

## W2.5 원본 지표

이벤트는 git에서 제외한 `.metrics/events.jsonl`에 한 줄씩 추가한다. `HARNESS_STATE_DIR`를 지정하면 활성 상태 파일과 같은 디렉터리에 쓴다. 파이프라인을 종료해도 이벤트는 남는다.

| `event` | 추가 필드 |
| --- | --- |
| `gate` | `outcome`은 `blocked`(차단), `passed`(검사 통과), `unresolved`(차단 상한 초과) 중 하나다. 검사를 실행한 경우 `source`(`ac` 또는 `path_rules`)와 `duration_seconds`(검사 명령 실행 시간)를 기록한다. `unresolved`는 task당 한 번 기록한다. |
| `task_ended` | `reason`은 `task_changed`(다음 task로 전환), `pipeline_ended`(종료), `replaced`(강제 재시작) 중 하나다. |

공통 필드는 `timestamp`(UTC), `run_id`, `plan`, `task_id`, `task_started_at`, `task_elapsed_seconds`, `tool`, `session_id`, `gate_blocks`(해당 task의 누적 차단 횟수)다. `task_elapsed_seconds`에는 세션을 이어 가기 전의 대기 시간도 들어간다. 같은 실행을 `resume`으로 이어 가면 `run_id`가 유지되고 세션 정보가 갱신된다.

작업 트리가 깨끗하거나 직전 통과 상태와 같거나 실행할 검사 명령이 없으면 게이트 시도에 포함하지 않는다. 지표 파일에 쓸 수 없으면 표준 오류에 원인을 남기고 게이트 판단은 그대로 전달한다.

## W4 집계 리포트

`harness/bin/report.py`가 `.metrics/events.jsonl`과 두 도구의 대화 기록을 읽어 계획마다 `<계획 slug>.md`를 만들고, 아래 요약 표를 갱신한다. 같은 입력이면 같은 출력이다. 리포트에는 세션 ID·경로·대화 내용을 넣지 않는다.

```bash
python3 harness/bin/report.py <계획 slug>...   # 인자를 생략하면 이벤트의 모든 계획
python3 harness/bin/report.py --help           # 이벤트·출력·대화 기록 경로 옵션
```

### 열 정의

- task 한 행은 `task_ended` 이벤트 하나가 닫는 구간이다. 재시작으로 같은 task ID가 다시 시작되면 구간마다 따로 행을 둔다. 종료 이벤트가 없는 구간은 집계하지 않는다.
- 벽시계 시간: `task_started_at`부터 `task_ended`까지다. 세션을 쉬던 대기 시간이 들어가므로 작업 시간이 아니다.
- 게이트 차단: `task_ended`의 `gate_blocks`다. AC 첫 시도 통과: 구간의 첫 `gate` 이벤트가 `passed`인지이며 게이트 이벤트가 없으면 `-`다. 미해결: 차단 상한을 넘겨 `unresolved`가 기록된 구간이다.
- 합계의 AC 첫 시도 통과는 판정이 있는 task 중 통과 수다.

### 토큰 정규화

| 항목 | Claude Code (`usage`) | Codex (`token_count`의 `total_token_usage`) |
| --- | --- | --- |
| 입력 | `input_tokens` | `input_tokens - cached_input_tokens` (Codex 입력은 캐시 읽기를 포함) |
| 캐시 읽기 | `cache_read_input_tokens` | `cached_input_tokens` |
| 캐시 쓰기 | `cache_creation_input_tokens` | `cache_write_input_tokens` |
| 출력 | `output_tokens` | `output_tokens` (추론 토큰 포함) |

- Claude Code는 같은 `message.id`가 여러 줄로 남으므로 항목별 최댓값 하나만 세고, 첫 시각이 구간에 드는 메시지를 더한다.
- Codex는 누적값이라 구간 끝 직전 값에서 구간 시작 직전 값을 뺀다. 시작 전 이벤트가 없으면 0에서 시작한다.
- 시범 세션 4,294개 이벤트에서 `total_tokens = input + output`과 추론 토큰이 출력에 포함된다는 가정을 확인했다.
- 기록을 못 찾거나 구간에 토큰 이벤트가 없으면 0이 아니라 "미상"이고 합계에서 제외한다.

### 한계

- 토큰은 `task_ended`의 세션 기록에서만 집계한다. task 도중 세션이 바뀌면 이전 세션의 토큰이 빠진다.
- 캐시 읽기는 같은 맥락을 매 호출 다시 읽는 양이라 입력보다 훨씬 크다. 도구마다 캐시 방식이 달라 항목끼리 더하거나 도구 간 총량을 비교하지 않는다.
- 지금까지의 이벤트는 모두 Codex다. Claude Code와의 차이는 위 필드 차이만 서술하며 표본이 없어 우열을 말하지 않는다. Claude Code로 진행한 계획이 생기면 같은 명령으로 리포트를 추가한다.
- 사람 개입 지표(질문 라운드, 구현 중 프롬프트, 커밋 후 사람 수정)는 수집하지 않는다.

### 계획별 요약

<!-- report:start -->
| 계획 | 도구 | task | 벽시계 시간 | 게이트 차단 | AC 첫 시도 통과 | 미해결 | 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| [core-payment-schema-cleanup](core-payment-schema-cleanup.md) | codex | 1 | 16분 23초 | 1 | 0/1 | 0 | 177,550 | 6,810,112 | 0 | 23,527 |
| [m6-settlement-split](m6-settlement-split.md) | codex | 7 | 53분 06초 | 0 | 7/7 | 0 | 435,899 | 29,728,256 | 0 | 95,357 |
| [m6-booking-payment-saga](m6-booking-payment-saga.md) | codex | 4 | 515분 14초 | 0 | 4/4 | 0 | 215,491 | 11,266,816 | 0 | 45,041 |
| [admin-enhancement](admin-enhancement.md) | codex | 10 | 69분 02초 | 1 | 9/10 | 0 | 521,641 | 28,172,288 | 0 | 93,946 |
| [poster-storage-rustfs](poster-storage-rustfs.md) | codex | 2 | 213분 05초 | 3 | 1/2 | 1 | 71,370 | 8,472,064 | 0 | 13,284 |
| [admin-cancel-saga](admin-cancel-saga.md) | codex | 7 | 44분 40초 | 1 | 6/7 | 0 | 542,257 | 25,026,432 | 0 | 81,506 |
<!-- report:end -->
