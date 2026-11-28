# core-payment-schema-cleanup 지표

<!-- summary: {"blocks": 1, "first_pass": 0, "judged": 1, "seconds": 983.0, "slug": "core-payment-schema-cleanup", "started_at": "2026-11-27T08:04:04+00:00", "tasks": 1, "tokens": {"cache_read": 6810112, "cache_write": 0, "input": 177550, "output": 23527}, "tools": ["codex"], "unknown_tokens": 0, "unresolved": 0} -->

| task | 도구 | 벽시계 시간 | 게이트 차단 | AC 첫 시도 통과 | 미해결 | 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |
| --- | --- | ---: | ---: | --- | --- | ---: | ---: | ---: | ---: |
| T1 | codex | 16분 23초 | 1 | 아니오 | 아니오 | 177,550 | 6,810,112 | 0 | 23,527 |
| **합계** | **codex** | **16분 23초** | **1** | **0/1** | **0** | **177,550** | **6,810,112** | **0** | **23,527** |

벽시계 시간에는 세션을 쉬던 대기 시간이 섞여 있어 작업 시간이 아니다. 토큰은 task 종료 시점의 세션 기록에서 구간만 집계하므로, task 도중 세션이 바뀌면 일부가 빠질 수 있다.
