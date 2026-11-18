# herdr 명령어 정리

herdr(v0.9.5 기준)를 다루라는 지시를 받았을 때 매번 검색하지 않도록 정리한 참고 문서다. **명령 문법의 최종 기준은 설치된 바이너리**이므로 의심스러우면 `herdr --help`, `herdr <그룹>`(서브커맨드 없이)으로 확인한다. 원문은 `herdr --skill`로 출력할 수 있다.

## 1. 시작 전 확인

```bash
test "${HERDR_ENV:-}" = 1   # 실패하면 herdr 밖이다. 제어 명령을 내리지 않고 사용자에게 알린다
```

- 맨 `herdr`는 TUI를 띄우거나 attach한다. 탐색용으로 실행하지 않는다.
- 변경 명령(`workspace create` 등)은 인자 없이도 실행된다. 도움말 확인 목적으로 인자 없이 호출하지 않는다.
- 대부분 JSON을 반환한다. ID와 상태는 응답에서 읽고 추측하지 않는다.
- 서버 오류는 stderr JSON + 종료 코드 1, 문법 오류는 종료 코드 2.

## 2. 개념과 ID

| 개념 | 설명 | ID 예 |
|---|---|---|
| workspace | 최상위 작업 공간 | `w1` |
| tab | workspace 안의 탭 | `w1:t1` |
| pane | 탭 안의 터미널 | `w1:p1` |
| agent | pane을 차지한 코딩 에이전트 | 이름(`[a-z][a-z0-9_-]{0,31}`, 살아 있는 에이전트 중 유일) 또는 pane ID |

- 닫힌 tab·pane ID는 재사용되지 않는다. `pane move` 후에는 `.result.move_result.pane.pane_id`(새 ID)를 쓴다.
- pane은 에이전트 유무와 상관없이 존재한다. 일반 프로세스(테스트·서버)는 `pane` 명령, 에이전트 제어는 `agent` 명령을 쓴다.
- 에이전트 상태: `idle`, `working`, `blocked`(승인·질문 UI 인식), `done`, `unknown`. `idle`/`done`은 입력 가능 상태이며, `unknown`은 완료의 증거가 아니다.
- 호출자 컨텍스트 환경변수: `HERDR_WORKSPACE_ID`, `HERDR_TAB_ID`, `HERDR_PANE_ID`.
- 호출한 pane을 대상으로 할 때는 `--current`를 쓴다. 그 외 명령은 사용자나 다른 클라이언트의 포커스 pane을 가리킬 수 있으므로 ID나 에이전트 이름을 명시한다.

## 3. 조회

```bash
herdr status [server|client]                       # 클라이언트·서버 상태와 버전
herdr workspace list
herdr tab list --workspace "$HERDR_WORKSPACE_ID"
herdr pane current --current
herdr pane list --workspace "$HERDR_WORKSPACE_ID"
herdr pane layout --pane "$HERDR_PANE_ID"          # 분할 방향 결정에 사용
herdr agent list
herdr agent get <이름|pane>
herdr agent explain <이름|pane>                    # 에이전트 감지 상태 설명
herdr api snapshot                                 # 라이브 세션 전체 스냅샷
```

## 4. 레이아웃(workspace / tab / pane)

```bash
herdr workspace create|get|focus|rename|close
herdr tab create|get|focus|rename|close
herdr pane split --current --direction right --cwd "$PWD" --no-focus   # 응답의 .result.pane.pane_id 사용
herdr pane focus|resize|zoom|swap|move|rename|close|neighbor|edges|process-info
```

- `workspace create`는 `.result.workspace`, `.result.tab`, `.result.root_pane`을, `tab create`는 `.result.tab`, `.result.root_pane`을 반환한다.
- 분할 방향: 넓은 pane은 `right`, 좁거나 긴 pane은 `down`. 같은 방향을 반복해 너무 좁은 열·행을 만들지 않는다.
- 기본은 **현재 탭의 형제 pane + 현재 작업 디렉터리**다. 사용자가 요청하지 않으면 workspace·tab·worktree를 새로 만들지 않는다.

## 5. 에이전트 시작·제어

```bash
# 1) 형제 pane 만들기 (사용자 포커스 유지)
herdr pane split --current --direction right --cwd "$PWD" --no-focus

# 2) 쉘 프롬프트 상태의 pane에서 에이전트 시작 (--kind는 `herdr agent`로 지원 목록 확인)
herdr agent start reviewer --kind codex --pane <pane-id>
herdr agent start reviewer --kind codex --pane <pane-id> -- <에이전트 고유 인자>

# 3) 작업 전달 (제출 후 settled 상태까지 대기)
herdr agent prompt reviewer "현재 diff를 리뷰하고 조치가 필요한 항목만 보고해라." --wait --timeout 120000

# 4) 상태 대기 / 키 전송 / 출력 읽기
herdr agent wait reviewer --until blocked --timeout 120000
herdr agent send-keys reviewer esc            # ctrl+c 등 논리 키. 모든 키를 검증한 뒤 기록
herdr agent read reviewer --source recent-unwrapped --lines 120
herdr agent rename|focus|attach ...
```

- `agent start`는 기존의 사용 가능한 쉘 pane이 필요하며 레이아웃을 만들거나 옮기지 않는다. 시작 타임아웃 기본값은 30초다.
- 시작 중 `blocked`면 `agent_not_ready`가 즉시 반환되지만 이름은 유지된다. idle이 될 때까지 기다린 뒤 프롬프트를 보낸다.
- `agent prompt --wait`는 처음 settled된 `idle`/`done`/`blocked`까지 기다린다. 기본값과 중복되는 `--until`은 붙이지 않는다.
- `agent_blocked`(승인 대화상자)는 내용을 확인하고 **사용자에게 물은 뒤** 응답한다.
- `agent_prompt_stalled`·`timeout`은 프롬프트가 전달되지 않았다는 증거가 아니다. 확인 없이 다시 보내지 않는다. `agent get`/`agent read`로 먼저 확인한다.

## 6. 일반 명령 실행 (pane)

```bash
herdr pane run <pane-id> "./gradlew build"                      # 명령 + Enter 큐잉 (실행 완료가 아니라 접수 확인)
herdr pane wait-output <pane-id> --match "BUILD SUCCESSFUL" --timeout 120000
herdr pane wait-output <pane-id> --regex "tests? completed" --timeout 120000
herdr pane read <pane-id> --source recent-unwrapped --lines 120
herdr pane send-text <pane-id> "literal text"
herdr pane send-keys <pane-id> ctrl+c
```

- `wait-output`은 이미 존재하는 출력도 매치한다. `--match`는 리터럴, `--regex`는 Rust 정규식이며, `--timeout`을 생략하면 무기한 대기한다.
- macOS에서는 터미널의 줄 입력 한도를 넘는 명령을 `pane run`·`agent start`가 큐잉 전에 거부한다. 쉘 프롬프트를 기다린 뒤 재시도한다.
- `--source` 값: `visible`(보이는 화면), `recent`(soft wrap 포함), `recent-unwrapped`(soft wrap 결합, 로그·대화에 권장), `detection`(에이전트 감지용 텍스트). 색상이 필요하면 `--format ansi`.
- 긴 응답이 `--lines`로도 안 보이면 에이전트에게 임시 디렉터리에 Markdown 파일로 쓰게 하고 경로만 답하게 한 뒤 그 파일을 읽는다(최후 수단).

## 7. worktree

```bash
herdr worktree list
herdr worktree create ...     # Git worktree를 만들고 workspace로 연다
herdr worktree open ...
herdr worktree remove ...
```

- `--trust-repository`는 사용자가 저장소를 확인한 뒤에만 쓴다. 실패한 worktree 명령의 재시도 수단이 아니다.
- `workspace close --group`은 주 workspace와 연결된 worktree workspace를 모두 닫는다. `workspace_group_close_required`를 우회하려는 용도로 붙이지 않는다.

## 8. 원격(SSH 머신) 제어

```bash
herdr --machine <label-or-id> agent list
herdr --machine <label-or-id> pane list
herdr --machine <label-or-id> agent prompt <원격-에이전트> "상태를 알려줘." --wait --timeout 120000
herdr machine list [--json]
herdr machine status [<label-or-id>] --json     # 연결 문제 진단
herdr machine add|rename|remove|enable|disable|reconnect
```

- 탐색부터 이후 모든 명령까지 같은 `--machine` 접두사를 쓴다. 로컬 ID와 `--current`는 원격 pane을 가리키지 않는다. `--session`·`--remote`와 함께 쓰지 않는다.
- 머신 프로필의 추가·삭제·활성화는 사용자가 요청할 때만 한다.
- `machine reconnect`는 사용자가 SSH 인증을 직접 해야 하므로 대신 실행하지 않는다.
- 연결 실패가 변경이 적용되지 않았다는 증거는 아니다. 재시도 전에 원격 상태를 확인한다.

## 9. 세션·서버·설정·기타

```bash
herdr --session <name>              # 이름 있는 세션 사용·생성 (실험은 별도 세션에서)
herdr session list|attach|stop|delete
herdr server reload-config          # config.toml 재적용
herdr config reset-keys             # config.toml 백업 후 사용자 키바인딩 제거
herdr update [--handoff]
herdr channel set <stable|preview>
herdr completion zsh
herdr notification show ...
herdr integration install|uninstall|status ...   # 내장 에이전트 연동
herdr api schema                    # 번들된 API 스키마
herdr --default-config              # 기본 설정 출력
```

- 설정 파일: `~/.config/herdr/config.toml`(`HERDR_CONFIG_PATH`로 변경 가능). 로그: `~/.config/herdr/herdr.log`, `herdr-client.log`, `herdr-server.log`.
- 소켓 API 상세: `herdr api schema`.

## 10. 안전 규칙

- 백그라운드 작업은 `--no-focus`로 사용자 포커스를 유지한다.
- 내가 만들지 않은 workspace·tab·pane·session은 사용자가 명시하지 않는 한 닫지 않는다. CLI 닫기에는 TUI의 마지막 pane 확인이 적용되지 않는다.
- 업데이트 후 클라이언트와 서버 버전이 다를 수 있다. 새 기능을 쓰기 전에 `herdr status`로 확인한다. 메서드가 없다는 사실이 서버를 중지·업그레이드해도 된다는 뜻은 아니다.
- 활성 세션에서 `herdr server stop`은 사용자가 서버와 pane 프로세스를 멈출 의도가 있을 때만 쓴다. 메인 herdr 프로세스를 kill하지 않는다.

## 11. 참고 링크

- 홈: https://herdr.dev
- 처음 설정·이해: https://herdr.dev/agent-guide.md
- 문제 조사: https://herdr.dev/llms.txt
