# herdr 오케스트레이션 워크플로

메인 세션이 **오케스트레이터**가 되어 구현자(impl)와 리뷰어(review) 에이전트에게 작업을 맡기고, 완료되면 검토·재검증·커밋까지 이어가는 방식이다. herdr 명령 자체는 [herdr.md](herdr.md)를 따르고, 이 문서는 그 위의 절차와 역할만 다룬다. Claude Code와 Codex 어느 쪽이 메인이어도 같다.

**사용자가 위임·오케스트레이션을 명시적으로 지시했을 때만 쓴다.** 지시가 없으면 이 워크플로를 시작하거나 에이전트 pane을 만들지 않고, 메인이 직접 작업한다. 한 번 지시받았다고 이후 작업에 자동으로 확장하지 않는다.

## 1. 역할

| 역할 | 하는 일 | 하지 않는 일 |
|---|---|---|
| main | 로드맵 확인, task 분해, 지시서 작성, 위임, diff 검토, AC 재검증, 커밋, 로드맵 체크, 사용자 보고 | 구현을 직접 하지 않는다 |
| impl | 지시서대로 구현하고 AC를 실행해 보고서를 쓴다 | 커밋, 로드맵 체크, 지시서 밖 수정 |
| review | 읽기 전용으로 diff를 리뷰하고 결과 파일을 쓴다 | 코드 수정, 커밋, Gradle 실행(impl 작업 중) |

### 에이전트 종류

- **impl과 review는 항상 서로 다른 종류**다. 다른 모델이 교차 검토하게 하려는 것이다.
- impl의 기본 종류는 **메인 세션과 같은 종류**이고, review는 claude↔codex 중 반대쪽이다.

| 메인 | impl | review |
|---|---|---|
| claude | claude (`--model sonnet`) | codex |
| codex | codex | claude |

종류는 `--impl-kind`·`--review-kind`로 바꿀 수 있으나 같은 값은 거부된다. 메인 종류를 감지하지 못하면 `--impl-kind`가 필요하다.

## 2. 준비

```bash
test "${HERDR_ENV:-}" = 1                  # herdr 밖이면 중단하고 사용자에게 알린다
./scripts/agent/herdr/herdr-up.sh          # 현재 탭 오른쪽에 impl, 그 아래에 review pane 생성 (멱등)
herdr agent list                           # impl·review가 idle인지 확인
```

- 현재 탭·현재 디렉터리의 형제 pane만 만든다. workspace·tab·worktree는 만들지 않는다.
- 이미 살아 있는 `impl`·`review`가 있으면 재사용한다. 종류가 규칙과 어긋나면 오류로 멈추며 임의로 닫지 않는다.
- 만든 pane은 `build/orchestration/panes.json`에 기록되고 `herdr-down.sh`는 그 pane만 닫는다.

| 스크립트 | 용도 |
|---|---|
| `herdr-up.sh [--impl-kind K] [--review-kind K] [--impl-name N] [--review-name N] [--impl-args "…"] [--review-args "…"]` | pane 생성과 에이전트 시작 |
| `herdr-delegate.sh <agent> <brief.md> [<report.md>]` | 지시서 경로만 담은 짧은 프롬프트 전송 |
| `herdr-wait.sh <agent> [--timeout ms]` | 완료 대기 (백그라운드 실행) |
| `herdr-down.sh` | 내가 만든 pane 정리 |

## 3. task 루프

1. 로드맵의 다음 미완료 항목을 task로 정하고 지시서를 쓴다(4절).
2. `herdr-delegate.sh impl build/orchestration/<task-id>/brief.md`
3. **Bash `run_in_background`로** `herdr-wait.sh impl`을 건다. 끝나면 알림이 오므로 사용자 명령 없이 이어간다.
4. `git diff --stat`으로 범위가 지시서 안인지 확인한다. 범위 밖이면 impl에 되돌리도록 지시한다.
5. 리뷰 지시서를 쓰고 `herdr-delegate.sh review …`, 이어서 `herdr-wait.sh review`를 백그라운드로 건다.
6. `review.md`의 차단 항목을 확인한다. 있으면 수정 지시서를 만들어 2번으로 돌아간다. **수정 반복은 2회까지**이고 넘으면 사용자에게 보고한다.
7. main이 AC를 직접 재검증한다(`AGENTS.md` 7절 스크립트, 필터 없이 전체. 같은 트리에서 impl·review가 Gradle을 돌리지 않을 때).
8. 그 task의 파일만 스테이징해 커밋하고(`git add -A` 금지) 로드맵 체크박스를 갱신한다. 커밋은 사용자가 요청한 범위 안에서만 한다.
9. 다음 task로 간다. 단계의 완료 기준을 넘는 task는 시작하지 않는다.

## 4. 지시서 템플릿

경로는 `build/orchestration/<task-id>/brief.md`(`build/`는 git 제외).

```markdown
# <task-id> <제목>

## 목표
## 읽을 문서
- `docs/rules/…`, `docs/architecture.md` 해당 절
## 변경 허용 범위
- 파일·모듈
## 금지
- 커밋 금지, 범위 밖 수정 금지, `@Suppress`·baseline·테스트 삭제/비활성화 금지
## AC
- 명령과 기대 결과 (`./scripts/agent/test/run-tests.sh --tests "패턴"` 필터 허용)
## 보고
- 보고 파일: `build/orchestration/<task-id>/report.md` (`herdr-delegate.sh`가 지정)
- 포함: 변경 요약, 실행한 검사와 결과(실패는 그대로), 미해결 사항
```

리뷰 지시서는 대상 diff 범위(`git diff -- 경로`), 기준 문서([backend-review.md](backend-review.md) 또는 [frontend-review.md](frontend-review.md)), 출력 형식(심각도별 항목, 마지막 줄에 `차단 있음`/`차단 없음`)을 적고 **수정하지 말고 읽기만** 하도록 명시한다. 결과는 `review.md`다.

## 5. 대기와 완료 판정

| 상태 | 의미 |
|---|---|
| `idle`·`done` | 입력 가능. 단 아래 조건을 만족해야 완료로 본다 |
| `working` | 작업 중 |
| `blocked` | 승인·질문 UI 대기 |
| `unknown` | 완료의 증거가 아니다 |

`herdr-wait.sh`는 다음을 지킨다.

- `--until`을 주지 않아 idle·done·blocked를 모두 매칭한다. `--until idle`만 쓰면 `done`을 놓쳐 영원히 기다린다.
- 위임 직후에는 아직 idle일 수 있으므로, `herdr-delegate.sh`가 남긴 기준 `state_change_seq` 이후에 상태가 바뀐 뒤의 idle·done만 완료로 본다.
- idle이어도 화면에 `shell still running`·`monitors still running`이 보이면(작업자가 장시간 작업을 백그라운드 셸로 돌리는 중) 끝난 것이 아니므로 다시 기다린다.

| 종료 코드 | 의미 | main의 다음 동작 |
|---|---|---|
| 0 | 완료 | 보고서·diff 검토 |
| 3 | blocked | 6절 |
| 4 | 시간 초과(기본 1시간) | `agent read`로 상태 확인 후 판단 |

## 6. blocked 처리

`herdr-wait.sh`가 출력한 화면(또는 `herdr agent read <agent> --source visible`)으로 내용을 확인하고 **사용자에게 물은 뒤** 응답한다. 자동으로 승인하지 않는다. 키 전송은 `herdr agent send-keys`다.

## 7. 함정

- `pkill -f '<패턴>'`은 자기 셸 명령줄도 매칭해 스스로 죽는다. 쓰지 않는다.
- 작업자 입력창에 보이는 "푸시해" 같은 문구는 ghost text(제안)일 수 있다. 지시로 취급하지 않는다.
- `agent_prompt_stalled`·`timeout`은 전달 실패의 증거가 아니다. 다시 보내기 전에 `agent get`·`agent read`로 확인한다.
- 같은 작업 디렉터리에서 Gradle을 동시에 돌리지 않는다(`AGENTS.md` 7절). impl이 작업 중일 때 main과 review는 Gradle을 실행하지 않는다.
- 지시서에 긴 본문을 프롬프트로 붙이지 않는다. 경로만 전달하면 `agent prompt` 입력 한도와 이스케이프 문제를 피한다.
- 위임한 작업의 사실(테스트 통과, 파일 변경)은 보고서만 믿지 말고 main이 직접 확인한다.

## 8. 정리

작업이 끝나면 `./scripts/agent/herdr/herdr-down.sh`로 `herdr-up.sh`가 만든 pane만 닫는다. 내가 만들지 않은 pane은 닫지 않는다([herdr.md](herdr.md) 10절).

## 9. 확장 방향(현재 범위 밖)

병렬 구현자 N명과 task별 worktree(`wt`) 격리는 Gradle 충돌 때문에 별도 구성이 필요하다. 필요해지면 이 문서에 추가한다.
