# 워크플로 지표 집계

계획 단위로 묶은 파이프라인 지표 집계를 둔다([ADR 0048](../../adr/0048-ai-dev-workflow-pipeline.md) 결정 8). 집계 형식은 W4에서 정한다.

## W2.5 원본 지표

이벤트는 git에서 제외한 `.metrics/events.jsonl`에 한 줄씩 추가한다. `HARNESS_STATE_DIR`를 지정하면 활성 상태 파일과 같은 디렉터리에 쓴다. 파이프라인을 종료해도 이벤트는 남는다.

| `event` | 추가 필드 |
| --- | --- |
| `gate` | `outcome`은 `blocked`(차단), `passed`(검사 통과), `unresolved`(차단 상한 초과) 중 하나다. 검사를 실행한 경우 `source`(`ac` 또는 `path_rules`)와 `duration_seconds`(검사 명령 실행 시간)를 기록한다. `unresolved`는 task당 한 번 기록한다. |
| `task_ended` | `reason`은 `task_changed`(다음 task로 전환), `pipeline_ended`(종료), `replaced`(강제 재시작) 중 하나다. |

공통 필드는 `timestamp`(UTC), `run_id`, `plan`, `task_id`, `task_started_at`, `task_elapsed_seconds`, `tool`, `session_id`, `gate_blocks`(해당 task의 누적 차단 횟수)다. `task_elapsed_seconds`에는 세션을 이어 가기 전의 대기 시간도 들어간다. 같은 실행을 `resume`으로 이어 가면 `run_id`가 유지되고 세션 정보가 갱신된다.

작업 트리가 깨끗하거나 직전 통과 상태와 같거나 실행할 검사 명령이 없으면 게이트 시도에 포함하지 않는다. 지표 파일에 쓸 수 없으면 표준 오류에 원인을 남기고 게이트 판단은 그대로 전달한다.
