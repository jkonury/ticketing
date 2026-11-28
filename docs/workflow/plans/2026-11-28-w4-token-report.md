---
status: approved
approved_at: 2026-11-28T21:43:28+09:00
---

# W4 토큰 수집과 집계 리포트

## 목표와 근거

[W 트랙 로드맵](../roadmap.md)의 W4 단계다. 파이프라인으로 끝낸 계획마다 task별 소요 시간·게이트 반복·AC 첫 시도 통과·토큰을 집계한 리포트를 `docs/workflow/metrics/`에 남긴다. 지표 정의는 [ADR 0048 결정 8](../../adr/0048-ai-dev-workflow-pipeline.md#결정)을 따르며 이 계획은 결정을 바꾸지 않는다. 원본은 `.metrics/events.jsonl`(git 제외)이고 토큰은 훅이 기록하지 않고 종료 후 두 도구의 대화 기록에서 사후 집계한다.

끝났을 때 관찰할 결과:

- `python3 harness/bin/report.py`가 `.metrics/events.jsonl`과 두 도구의 대화 기록을 읽어 계획별 리포트를 만든다.
- 완료 기준의 시범 계획(`core-payment-schema-cleanup`)을 포함해 완주한 계획 6건의 리포트가 `docs/workflow/metrics/`에 있고 README에 계획별 요약 표가 있다.
- 두 도구의 토큰 필드를 같은 항목(입력·캐시 읽기·캐시 쓰기·출력)으로 맞추는 규칙과 집계 한계가 문서에 있다.

## 범위

- 포함: `harness/lib`의 대화 기록 토큰 집계(Claude Code·Codex), `harness/bin/report.py`와 단위 테스트, 완주한 계획 6건의 리포트(`core-payment-schema-cleanup`, `m6-settlement-split`, `m6-booking-payment-saga`, `admin-enhancement`, `poster-storage-rustfs`, `admin-cancel-saga`), 지표 README의 집계 형식, 로드맵 W4 체크.
- 범위 밖: 사람 개입 지표(질문 라운드·구현 중 프롬프트·커밋 후 사람 수정, 로드맵에 미룬 항목으로 적는다), 훅·`events.jsonl` 형식 변경, 도구 간 우열 주장을 위한 같은 계획의 재구현, 토큰 금액 환산, W5·W6.
- 이 시점의 `.metrics/`는 모든 이벤트가 Codex다. Claude Code로 진행한 계획이 생기면 같은 스크립트로 리포트를 추가한다. 이번 계획에서는 그 절차와 파서 테스트로만 도구 간 차이를 다룬다.

## task

### T1. 대화 기록의 task별 토큰 집계

- [ ] 완료
- Blocked by: 없음
- 변경 범위와 제약: `harness/lib/transcripts.py`를 추가한다. 공개 함수는 도구·세션 ID·시간 범위·기록 디렉터리를 받아 정규화한 토큰(입력, 캐시 읽기, 캐시 쓰기, 출력)을 돌려준다. 기록을 못 찾거나 범위에 토큰 이벤트가 없으면 값이 아니라 "미상"을 돌려주고 0으로 속이지 않는다. 세션 파일은 Claude Code가 `~/.claude/projects/*/<세션 ID>.jsonl`, Codex가 `~/.codex/sessions/*/*/*/rollout-*-<세션 ID>.jsonl`이므로 세션 ID로 찾는다. Claude Code는 assistant 메시지의 `usage`를 `message.id` 기준 중복 제거해 시각이 범위에 드는 것만 더한다(`input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, `output_tokens`). Codex는 `token_count` 이벤트의 `total_token_usage` 누적값에서 범위 시작 직전 값과 끝 직전 값의 차를 쓰고, 입력은 캐시 읽기를 포함한 값이므로 `input_tokens - cached_input_tokens`를 입력으로 정규화한다. 구현 전에 시범 세션 한두 개로 `total_tokens = input + output`과 `last_usage_includes_reasoning` 가정을 확인하고, 다르면 사용자와 규칙을 다시 정한다. 대화 내용은 읽되 결과에는 숫자만 남긴다. 이 task는 `.metrics/`와 훅을 건드리지 않는다.
- 테스트 경계와 선례: `harness/tests/test_transcripts.py`(신규)가 합성 대화 기록 파일로 공개 함수를 검증한다. 선례는 `harness/tests/test_gate.py`·`support.py`의 임시 디렉터리 방식이다. 검증할 것: Claude Code의 중복 메시지 제거와 범위 경계, Codex의 누적 차분과 캐시 정규화, 같은 이벤트 반복에도 이중 집계 없음, 세션 파일 없음·범위에 이벤트 없음일 때 "미상".
- AC:

  ```bash
  python3 -m unittest discover -s harness/tests -q
  ```

### T2. 계획별 리포트 생성 스크립트

- [ ] 완료
- Blocked by: T1
- 변경 범위와 제약: `harness/bin/report.py`를 추가한다. 인자는 `.metrics/events.jsonl` 경로, 출력 디렉터리, 두 대화 기록 루트(기본은 사용자 홈의 도구 디렉터리), 대상 계획 목록이다. 이벤트를 `run_id`와 `plan`으로 묶고 task 구간(`task_started_at`부터 `task_ended`의 `timestamp`)마다 소요 시간, 게이트 차단 횟수(`gate_blocks`의 마지막 값), 첫 게이트가 `passed`였는지(AC 첫 시도 통과), `unresolved` 여부, 정규화 토큰을 계산한다. task ID가 구간마다 반복돼도(예: 재시작) 구간을 따로 행으로 둔다. 소요 시간에는 세션을 쉬던 대기 시간이 섞이므로(지표 README에 기록된 사실) 표 머리에 "벽시계 시간"으로 표기하고 리포트에 한계를 한 줄 적는다. 출력은 `<계획 slug>.md` 하나(task 표와 합계 행)와 `README.md`의 계획별 요약 표 갱신이다. 계획 요약 표 갱신은 스크립트가 맡는 영역을 표식 주석으로 구분해 README의 손으로 쓴 설명을 덮어쓰지 않는다. 리포트에는 세션 ID·경로·대화 내용을 넣지 않고 숫자와 도구 이름만 쓴다. 토큰 미상은 표에 "미상"으로 쓰고 합계에서 제외했음을 표기한다. 같은 입력에 같은 출력을 내야 한다(재생성해도 diff가 없다).
- 테스트 경계와 선례: `harness/tests/test_report.py`(신규)가 합성 `events.jsonl`과 합성 대화 기록으로 스크립트의 출력 파일을 검증한다. 선례는 `test_stop_gate.py`의 `load_script` 방식이다. 검증할 것: task 표의 각 열, 합계와 미상 처리, 구간이 반복되는 task의 별도 행, README 표식 영역만 갱신, 재실행 시 출력 동일, 출력에 세션 ID가 없음.
- AC:

  ```bash
  python3 -m unittest discover -s harness/tests -q
  ```

### T3. 완주한 계획 6건의 리포트와 문서

- [ ] 완료
- Blocked by: T2
- 변경 범위와 제약: 로컬 `.metrics/events.jsonl`과 대화 기록으로 `report.py`를 실행해 6개 계획의 리포트와 README 요약 표를 `docs/workflow/metrics/`에 만든다. 결과 숫자를 눈으로 확인한다. 합계가 음수이거나 task 시간 대비 토큰이 0에 가깝거나 미상이 많으면 파서나 구간 계산 가정을 고치고, 고칠 수 없는 한계는 리포트에 적는다. 같은 계획의 숫자를 맞추려고 파서를 반복해서 고치지 않고, 두 번 수정해도 설명되지 않으면 멈추고 사용자에게 묻는다. [지표 README](../metrics/README.md)에 집계 형식(열 정의, 토큰 정규화 규칙, 한계, 새 계획을 추가하는 명령, Claude Code 계획이 생겼을 때의 절차)을 적고 도구 간 비교는 필드 차이와 표본 한계만 서술한다. [로드맵](../roadmap.md)의 W4 항목을 체크하고 상태 줄을 갱신하며 미룬 사람 개입 지표 항목을 적는다.
- 테스트 경계와 선례: 문서 링크·표기 검사와 하네스 단위 테스트. 리포트 숫자는 원본 이벤트와 일치하는지 한 계획(`core-payment-schema-cleanup`)을 직접 대조해 확인한다.
- AC:

  ```bash
  python3 harness/bin/report.py --help
  python3 -m unittest discover -s harness/tests -q
  ./scripts/agent/docs/check-docs.sh
  ```

## 최종 검증

- `./scripts/agent/test/verify-changed.sh <시작-SHA>..HEAD`를 실행한다. 변경이 `harness/`와 `docs/`뿐이므로 JVM 전체 테스트는 필요하지 않다.
- `report.py`를 같은 입력으로 두 번 실행해 `docs/workflow/metrics/`에 diff가 없음을 확인한다.
- `review-diff`로 시작 SHA부터 HEAD까지 검토한다. 이 계획과 ADR 0048 결정 8을 명세로 쓴다.
- E2E는 이 변경과 무관하다.
