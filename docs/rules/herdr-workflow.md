# herdr 오케스트레이션 워크플로

메인 세션이 **오케스트레이터**가 되어 구현자(impl)와 리뷰어(review) 에이전트에게 작업을 맡기고, 완료되면 검토·재검증·커밋까지 이어가는 방식이다. herdr 명령 자체는 [herdr.md](herdr.md)를 따르고, 이 문서는 그 위의 절차와 역할만 다룬다. Claude Code와 Codex 어느 쪽이 메인이어도 같다.

**사용자가 위임·오케스트레이션을 명시적으로 지시했을 때만 쓴다.** 지시가 없으면 이 워크플로를 시작하거나 에이전트 pane을 만들지 않고, 메인이 직접 작업한다. 한 번 지시받았다고 이후 작업에 자동으로 확장하지 않는다.

## 1. 역할

| 역할 | 하는 일 | 하지 않는 일 |
|---|---|---|
| main | 로드맵 확인, task 분해, 지시서 작성, 위임, diff 검토, AC 재검증, 커밋, 로드맵 체크, 사용자 보고 | 구현을 직접 하지 않는다 |
| impl | 지시서대로 구현하고 AC를 실행해 보고서를 쓴다 | 커밋, 로드맵 체크, 지시서 밖 수정 |
| review | 읽기 전용으로 diff와 인접 코드를 리뷰하고 결과 파일을 쓴다 | 코드 수정, 커밋, Gradle 실행(impl 작업 중) |

### 에이전트 종류

- **impl과 review는 항상 서로 다른 종류**다. 다른 모델이 교차 검토하게 하려는 것이다. 다만 종류가 다르다고 리뷰가 독립적이거나 정확하다는 보장은 없다. 품질은 리뷰 범위, 완료 판정, 검증 증거, 수정 반복 규칙이 좌우한다.
- impl의 기본 종류는 **메인 세션과 같은 종류**이고, review는 claude↔codex 중 반대쪽이다.

| 메인 | impl | review |
|---|---|---|
| claude | claude (`--model sonnet`) | codex |
| codex | codex | claude |

종류는 `--impl-kind`·`--review-kind`로 바꿀 수 있으나 같은 값은 거부된다. 메인 종류를 감지하지 못하면 `--impl-kind`가 필요하다.

## 2. 실행 범위

기본값은 **`single-task`·`no-commit`**이다. 사용자가 명확히 요청했을 때만 넓힌다.

| 사용자 지시 | 허용 동작 |
|---|---|
| "이 작업을 리뷰해 줘" | 리뷰만 |
| "이 작업을 herdr로 구현해 줘" | 구현·리뷰·재검증까지. 커밋하지 않는다 |
| "이 작업을 herdr로 구현하고 커밋해 줘" | 지정한 task 하나의 커밋까지 |
| "M6의 payment 분리를 끝까지 진행하고 task별 커밋해 줘" | 해당 범위 안의 연속 task 실행과 task별 커밋 |

- 시작할 때 main이 해석한 범위(단일/다중, 커밋 여부)를 사용자에게 한 줄로 알린다.
- `multi-task`와 `auto-commit`은 지시에 범위가 명시된 경우만 켠다. 그 범위를 넘는 task는 시작하지 않는다.
- 공유 작업 트리에서 task별 커밋 권한이 없으면 단일 task의 구현·리뷰·최종 검증까지 마친다. 독립적으로 검증하고 커밋할 수 있는 수정은 권한이 있을 때 각각 별도 task로 실행한다.
- 커밋 규칙은 `AGENTS.md` 7절(커밋은 사용자가 요청할 때만)을 따른다.

### 공용 실행 프롬프트

Claude Code 또는 Codex 메인에 아래 입력을 채워 전달한다.

```text
docs/rules/herdr-workflow.md에 따라 아래 범위를 herdr로 위임해 완료해 줘.

- 리뷰 파일 경로: <저장소 기준 경로>
- 작업 항목과 실행 순서: <task별 항목을 순서대로, 각 task의 완료 기준 포함>
- 설계 합의가 필요한 항목: <항목과 합의 필요 사항 또는 없음>
- task별 로컬 커밋 권한: <허용하는 task 목록 또는 없음>

설계 합의가 필요한 항목은 합의한 뒤 시작해. 구현자와 리뷰어는 서로 다른 종류로 실행해.
각 task의 리뷰와 최종 검증을 마치고, 권한이 있는 task는 로컬 커밋 후 다음 작업으로 넘어가.
검사 결과와 해당 로그 경로를 남기고, 리뷰 차단이나 필수 검사·커밋 훅 실패는 해당 task에서 해결해.
```

코어 리뷰 사례에서는 `#2 → #4 → #5 → #7 → #6`을 각각 검증·커밋하고, `#1·#3`은 예약 회차와 진행 중 제거 설계에 합의한 뒤 하나의 쿠폰 Saga task로 진행한다.

## 3. 준비

```bash
test "${HERDR_ENV:-}" = 1                  # herdr 밖이면 중단하고 사용자에게 알린다
git status --porcelain=v1 -uall            # 비어 있어야 자동 진행한다 (아래)
./scripts/agent/herdr/herdr-up.sh          # 현재 탭 오른쪽에 impl, 그 아래에 review pane 생성 (멱등)
herdr agent list                           # impl·review가 idle인지 확인
```

- 현재 탭·현재 디렉터리의 형제 pane만 만든다. workspace·tab·worktree는 만들지 않는다.
- 이미 살아 있는 `impl`·`review`가 있으면 재사용한다. 종류가 규칙과 어긋나면 오류로 멈추며 임의로 닫지 않는다.
- 만든 pane은 `build/orchestration/panes.json`에 기록되고 `herdr-down.sh`는 그 pane만 닫는다.
- 가드를 쓰는 실행은 깨끗한 작업 트리에서 시작한다. `build/`처럼 Git에서 제외한 생성물은 상태 검사에 포함되지 않는다. 사용자가 기존 변경을 둔 채 독립 위임을 진행하라고 하면 위치 인자 호출을 사용하고, 시작 변경 목록을 기록해 해당 경로를 위임 범위에서 제외한다.
- 실행마다 `build/orchestration/<run-id>`를 만들고 그 바로 아래에 task 디렉터리를 둔다. 한 실행은 한 메인이 순차로 진행한다. 모든 구현·리뷰·수정 위임에 같은 실행 디렉터리의 `--task`를 사용한다.
- 리뷰 기준 문서([backend-review.md](backend-review.md) 또는 [frontend-review.md](frontend-review.md))가 있고 읽히는지 확인한다.

| 스크립트 | 용도 |
|---|---|
| `herdr-up.sh [--impl-kind K] [--review-kind K] [--impl-name N] [--review-name N] [--impl-args "…"] [--review-args "…"]` | pane 생성과 에이전트 시작 |
| `herdr-delegate.sh [--task <task-dir>] <agent> <brief.md> [<report.md>]` | 경로를 담은 짧은 프롬프트 전송. `--task`는 실행의 Git 상태를 확인한다. 위치 인자만 주면 독립 위임으로 동작한다 |
| `herdr-wait.sh <agent> [--timeout ms]` | 실행 종료 대기 (백그라운드 실행) |
| `herdr-down.sh` | 내가 만든 pane 정리 |

## 4. task 루프

1. 다음 task를 정하고(로드맵 항목이면 다음 미완료 항목) 지시서를 쓴다(5절). `task_dir=build/orchestration/<run-id>/<task-id>`로 두고, 그 안의 `base.txt`에 기준 `HEAD`, 시작 시 `git status --porcelain=v1 -uall` 출력, 허용 경로 목록을 기록한다.
2. `./scripts/agent/herdr/herdr-delegate.sh --task "$task_dir" impl "$task_dir/brief.md"`로 구현을 맡긴다.
3. `herdr-wait.sh impl`을 메인 클라이언트의 백그라운드 명령으로 실행하고, 결과가 도착하면 이어간다. 완료 판정은 6절을 따른다. 구현이 끝나면 컨텍스트를 점검한다(4절 하단).
4. 변경 범위가 지시서 안인지 확인한다. `git diff`는 신규 파일과 staged 변경을 놓치므로 다음을 함께 본다. 범위 밖이면 impl에 되돌리도록 지시한다.
   ```bash
   git status --porcelain=v1 -uall
   git diff HEAD --stat
   git ls-files --others --exclude-standard
   ```
5. 리뷰 지시서를 쓰고 `./scripts/agent/herdr/herdr-delegate.sh --task "$task_dir" review "$task_dir/review-brief.md"`로 맡긴다. `herdr-wait.sh review`를 백그라운드로 실행하고, 리뷰가 끝나면 컨텍스트를 점검한다.
6. `review.md`에 차단 항목이 있으면 수정 지시서(`fix-N/brief.md`)를 만들어 2번으로 돌아간다. `--task`에는 계속 원래 `task_dir`를 전달한다. 수정이 끝나면 컨텍스트를 점검하고 다시 리뷰한다.
   - **초기 구현 1회 + 후속 수정 최대 2회**다. 후속 수정에는 리뷰 차단 사항과 main 재검증 실패가 모두 포함된다.
   - 2회를 넘으면 자동으로 더 시도하지 않고 남은 문제를 사용자에게 보고한다.
7. 리뷰 차단이 해소되면 main이 검증할 변경을 확정한다. 완료 기준을 충족한 로드맵 항목은 해당 체크박스를 갱신하고 그 변경도 검토한다. 커밋 권한이 있으면 리뷰를 마친 경로만 명시해 스테이징한다. `git diff --cached`로 기준 `HEAD` 대비 변경이 리뷰에서 확인한 내용과 일치하는지 확인하고 `git write-tree`로 인덱스 트리 ID를 기록한다. `git diff --quiet --ignore-submodules=none`이 통과하고 `git ls-files --others --exclude-standard` 출력이 비어 있어야 검증을 시작한다.
8. main은 리뷰된 변경에서 7절의 최종 AC를 순차 실행한다. impl의 관련 회귀 검사 결과는 보고서에서 확인한다. Docker 미기동·포트 충돌·Testcontainers 실패 같은 환경 문제는 수정 횟수에 세지 않는다. 검사가 끝날 때마다 결과와 로그를 보관한다(7절). 필수 검사 실패나 리뷰 차단이 남아 있는 동안에는 해당 task에 머문다.
9. 최종 AC 통과 후 커밋 권한이 있으면 `HEAD`가 시작값인지, `git write-tree` 값이 검증 전과 같은지, unstaged·untracked 변경이 없는지 다시 확인한다. 내용이 바뀌면 영향을 받는 리뷰와 검증을 다시 진행한다. 커밋 훅이 통과한 뒤 커밋의 트리(`git rev-parse 'HEAD^{tree}'`)가 검증한 트리와 같은지, 작업 트리가 깨끗한지 확인한다. 훅이 실패하거나 검증과 커밋 내용이 다르면 해당 task에서 멈춘다. 커밋 권한이 없으면 검증 결과와 미커밋 상태를 보고한다.
10. 실행 범위가 다중 task이고 직전 task를 마무리했을 때만 다음 task로 간다. 단계의 완료 기준을 지킨다.

### 위임 전 Git 검사

`--task`는 `task_dir`의 부모에 있는 `active-task.json`에 현재 task 경로와 시작 `HEAD`를 기록한다. 첫 task는 staged·unstaged·untracked 변경이 모두 없을 때 시작한다. 같은 task의 구현·리뷰·수정은 작업 트리에 변경이 있어도 `HEAD`가 시작값과 같으면 허용한다. 다음 task는 현재 `HEAD`의 유일한 부모가 직전 task의 시작 `HEAD`이고 작업 트리가 깨끗할 때 허용한다. 차단되면 해당 사유를 출력하고 보고서를 지우거나 프롬프트를 보내기 전에 종료한다.

### 컨텍스트와 토큰

구현·리뷰·수정이 끝날 때마다 메인·구현·리뷰 세션의 컨텍스트 사용률을 확인한다. 남은 비율만 표시되면 `100% - 남은 비율`로 계산한다.

| 도구 | 사용률 확인 | 같은 task 압축 | 새 대화 |
|---|---|---|---|
| Claude Code | `/context` | `/compact` | `/clear` |
| Codex CLI | `/status` | `/compact` | `/new` |

- 20%부터 해당 task의 `progress.md`에 현재 task, 결정, 검증 결과와 로그 경로, 다음 행동을 남긴다.
- 25% 이상에 도달한 세션은 실행 중인 명령이 끝난 뒤 정리한다. 같은 task를 계속하면 압축하고, 다음 task는 새 대화에서 시작한다. 압축 후에도 25% 이상이면 `progress.md`를 바탕으로 같은 task를 새 대화에서 재개한다. 사용률을 확인할 수 없는 클라이언트는 task 경계마다 새 대화를 사용한다.
- 25%는 체크포인트에서 정리를 시작하는 기준이다. 자동 압축 설정은 Claude Code 30%와 현재 Codex 설정을 유지한다.
- 첫 적용에서는 클라이언트·모델별 최대 관측 사용률, 압축 횟수, 새 대화 횟수를 실행 디렉터리에 기록한다. 클라이언트가 제공하는 입력·캐시 입력·출력 토큰도 함께 기록한다.

[Claude Code 명령](https://code.claude.com/docs/en/commands) · [Codex CLI 명령](https://learn.chatgpt.com/docs/developer-commands?surface=cli)

## 5. 지시서 템플릿

경로는 `<task-dir>/brief.md`다. 본문은 파일에 두고 위임 메시지에는 지시서 경로를 전달한다. 지시서에 `herdr-delegate.sh`가 지울 보고서 경로를 적는다. 기본값은 지시서와 같은 디렉터리의 `report.md`이며, 에이전트 이름이 `review`이면 `review.md`다. 다른 경로를 쓰면 호출의 보고서 인자에도 같은 경로를 준다. 결과 채팅은 판정과 로그 경로만 전달한다. 지시서 작성 원칙은 [서브에이전트 위임 지침](subagent-delegation.md)을 따른다.

```markdown
# <task-id> <제목>

## 목표
## 기준 커밋
- `<시작 HEAD>`
## 읽을 문서
- `docs/rules/<해당 지침> § <관련 절>`, `docs/architecture.md § <관련 절>`
## 변경 허용 범위
- 파일·모듈
## 금지
- 커밋 금지, 범위 밖 수정 금지, `@Suppress`·baseline·테스트 삭제/비활성화 금지
## AC
- 관련 회귀 검사의 명령과 기대 결과 (저장소 테스트 규칙에 맞춰 실행)
## 보고
- 보고 파일은 `<task-dir>/report.md`다.
- 형식: 첫 줄 `# <task-id> 보고`, 마지막 줄 `결과: 완료` 또는 `결과: 실패`
- 포함: 변경 요약, 실행한 검사와 결과(실패는 그대로), 미해결 사항
```

### 리뷰 지시서

목표, 기준 커밋, 대상 diff 범위(기준 커밋 대비 변경과 신규 파일), 기준 문서의 관련 절([backend-review.md](backend-review.md) 또는 [frontend-review.md](frontend-review.md)), 확인할 검증 명령과 출력 형식을 적고 읽기 전용으로 지시한다. 호출자와의 계약, 이벤트 소비자, 기존 테스트와의 충돌을 확인할 때 인접 코드와 ADR을 참고한다. 결과는 `review.md`다.

리뷰 기준:

1. 지시서의 목표와 AC를 만족하는가
2. 변경 범위를 벗어나지 않았는가
3. 설계·ADR·모듈 경계를 지키는가
4. 동시성·트랜잭션·재시도·실패 흐름이 안전한가
5. 테스트가 실제 위험을 검증하는가

특히 `(회차, 좌석)` 유니크 제약, Redis Lua 원자성, DB 커밋과 Redis 반영 시점, 이벤트·웹훅 멱등성 같은 불변 조건을 확인하게 한다.

출력 형식:

```markdown
# Review

## 판정
## 차단 사항
### R-001 [P0] 제목
- 파일 / 발생 조건 / 근거 / 실제 영향 / 수정 방향 / 관련 AC
## 비차단 사항
- …
(마지막 줄) 차단 있음 | 차단 없음
```

- P0: 정합성·보안·데이터 손실 위험. P1: AC 위반이나 중대한 기능 결함. P2: 개선 제안(차단 아님).
- **차단 결론에는 구체적인 발생 조건과 코드 근거가 필수**다. 추측이나 취향만으로 수정 루프를 돌리지 않는다.

## 6. 대기와 완료 판정

완료는 두 단계로 판정한다. 에이전트가 idle이 됐다고 지시받은 작업이 정상 완료된 것은 아니다(테스트 실패 후 보고서 없이 멈췄을 수 있다).

| 단계 | 확인할 증거 |
|---|---|
| 실행 종료 | 상태 변화 이후 `idle`·`done` (`herdr-wait.sh` 종료 코드 0) |
| 작업 완료 | 이번 위임의 보고 파일이 존재하고(delegate가 이전 파일을 지우므로 존재하면 이번 실행 것), 첫 줄의 task-id가 맞고, 마지막 줄 결과가 있고, main이 실제 diff를 확인 |

보고서가 없거나 형식이 틀리면 성공으로 보지 않고 `herdr agent read`로 상태를 확인한 뒤 판단한다. 보고서는 참고 증거일 뿐이고 테스트 통과는 main이 직접 검증한다.

| 상태 | 의미 |
|---|---|
| `idle`·`done` | 입력 가능. 단 위 조건을 만족해야 완료로 본다 |
| `working` | 작업 중 |
| `blocked` | 승인·질문 UI 대기 |
| `unknown` | 완료의 증거가 아니다 |

`herdr-wait.sh`는 다음을 지킨다.

- `--until`을 주지 않아 idle·done·blocked를 모두 매칭한다. `--until idle`만 쓰면 `done`을 놓쳐 영원히 기다린다.
- 위임 직후에는 아직 idle일 수 있으므로, `herdr-delegate.sh`가 남긴 기준 `state_change_seq` 이후에 상태가 바뀐 뒤의 idle·done만 완료로 본다.
- idle이어도 화면에 `shell still running`·`monitors still running`이 보이면(작업자가 장시간 작업을 백그라운드 셸로 돌리는 중) 끝난 것이 아니므로 다시 기다린다.

| 종료 코드 | 의미 | main의 다음 동작 |
|---|---|---|
| 0 | 실행 종료 | 보고서·diff 검토(작업 완료 판정) |
| 3 | blocked | 8절 |
| 4 | 시간 초과(기본 1시간) | `agent get`·`agent read`로 기존 실행이 살아 있는지 확인한 뒤 판단. 확인 전에 재위임하지 않는다 |

blocked·unknown·시간 초과는 어느 것도 성공이 아니다.

## 7. 최종 검증 범위

main의 최종 검증은 변경 유형에 따라 다음을 모두 통과해야 한다. 일반 `check`에는 E2E·부하 테스트가 포함되지 않는다.

| 변경 유형 | main 최종 검증 |
|---|---|
| Kotlin 일반 변경 | `./scripts/agent/test/run-tests.sh`(필터 없이) + `./scripts/agent/test/check-summary.sh` |
| Modulith 모듈 경계·이벤트 변경 | 위 검사 + 구조 검증, 필요 시 `./gradlew :core:modulithDocs` |
| core·gateway·realtime 핵심 흐름 변경 | 위 검사 + `./scripts/e2e/run.sh` |
| `web` 변경 | `./scripts/agent/test/web-check.sh`, 영향 시 `--web` E2E |
| 성능 최적화 | 기능·정합성 검사 후 단독 부하 측정(`scripts/loadtest/run.sh`) |
| 문서 변경 | `./scripts/agent/docs/check-docs.sh [경로...]` |

실행하지 않은 검사는 최종 보고에 건너뛴 항목으로 적는다.

검사가 끝날 때마다 명령, 종료 코드, 결과와 실패 테스트 이름을 task 디렉터리에 기록한다. 공용 파일은 다음 실행이 덮어쓰기 전에 그 task의 `logs/`에 복사한다. 재실행할 때는 시도별 이름을 붙인다. 보관 대상은 테스트 결과와 `build/run-tests/gradle-test-out.txt`, `build/check-summary/gradle-out.txt`, E2E의 `build/e2e/logs` 등 실제 사용한 로그다.

## 8. blocked 처리

`herdr-wait.sh`가 출력한 화면(또는 `herdr agent read <agent> --source visible`)으로 내용을 확인하고 **사용자에게 물은 뒤** 응답한다. 사용자 응답 전에는 자동 승인도 재위임도 하지 않는다. 키 전송은 `herdr agent send-keys`다.

## 9. 함정

- `pkill -f '<패턴>'`은 자기 셸 명령줄도 매칭해 스스로 죽는다. 쓰지 않는다.
- 작업자 입력창에 보이는 "푸시해" 같은 문구는 ghost text(제안)일 수 있다. 지시로 취급하지 않는다.
- `agent_prompt_stalled`·`timeout`은 전달 실패의 증거가 아니다. 다시 보내기 전에 `agent get`·`agent read`로 확인한다.
- 같은 작업 디렉터리에서 Gradle을 동시에 돌리지 않는다(`AGENTS.md` 6절). impl이 작업 중일 때 main과 review는 Gradle을 실행하지 않는다.
- 지시서에 긴 본문을 프롬프트로 붙이지 않는다. 경로만 전달하면 `agent prompt` 입력 한도와 이스케이프 문제를 피한다.
- 위임한 작업의 사실(테스트 통과, 파일 변경)은 보고서만 믿지 말고 main이 직접 확인한다.

## 10. 정리와 최종 보고

작업이 끝나면 `./scripts/agent/herdr/herdr-down.sh`로 `herdr-up.sh`가 만든 pane만 닫는다. 내가 만들지 않은 pane은 닫지 않는다([herdr.md](herdr.md) 10절). 성공·실패 모두 정리하되, **`blocked` 상태의 pane은 사용자 응답을 위해 보존**한다.

최종 보고에는 변경 파일, 검증 결과, 실행하지 않은 검사, 미해결 문제, 커밋 여부를 적는다.

## 11. 확장 방향(현재 범위 밖)

병렬 구현자 N명과 task별 worktree(`wt`) 격리는 Gradle 충돌 때문에 별도 구성이 필요하다. 필요해지면 이 문서에 추가한다. worktree로 격리하면 3절의 "깨끗한 트리" 제약도 완화할 수 있다.
