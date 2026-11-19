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
- 커밋 규칙은 `AGENTS.md` 7절(커밋은 사용자가 요청할 때만)을 따른다.

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
- **작업 트리가 깨끗하지 않으면 자동 진행을 멈추고 사용자에게 묻는다.** 같은 트리를 공유하므로 사용자 변경과 impl 변경을 구분할 수 없기 때문이다. `build/` 같은 git 제외 생성물은 무관하다. 사용자가 기존 변경을 둔 채 진행하라고 하면 그 목록을 기준선으로 기록하고, 해당 경로는 task 범위에서 제외한다.
- 리뷰 기준 문서([backend-review.md](backend-review.md) 또는 [frontend-review.md](frontend-review.md))가 있고 읽히는지 확인한다.

| 스크립트 | 용도 |
|---|---|
| `herdr-up.sh [--impl-kind K] [--review-kind K] [--impl-name N] [--review-name N] [--impl-args "…"] [--review-args "…"]` | pane 생성과 에이전트 시작 |
| `herdr-delegate.sh <agent> <brief.md> [<report.md>]` | 지시서 경로만 담은 짧은 프롬프트 전송. 이전 보고 파일은 먼저 지운다 |
| `herdr-wait.sh <agent> [--timeout ms]` | 실행 종료 대기 (백그라운드 실행) |
| `herdr-down.sh` | 내가 만든 pane 정리 |

## 4. task 루프

1. 다음 task를 정하고(로드맵 항목이면 다음 미완료 항목) 지시서를 쓴다(5절). `build/orchestration/<task-id>/base.txt`에 기준 `HEAD`, 시작 시 `git status --porcelain=v1 -uall` 출력, 허용 경로 목록을 기록한다.
2. `herdr-delegate.sh impl build/orchestration/<task-id>/brief.md`
3. **Bash `run_in_background`로** `herdr-wait.sh impl`을 건다. 끝나면 알림이 오므로 사용자 명령 없이 이어간다. 완료 판정은 6절을 따른다.
4. 변경 범위가 지시서 안인지 확인한다. `git diff`는 신규 파일과 staged 변경을 놓치므로 다음을 함께 본다. 범위 밖이면 impl에 되돌리도록 지시한다.
   ```bash
   git status --porcelain=v1 -uall
   git diff HEAD --stat
   git ls-files --others --exclude-standard
   ```
5. 리뷰 지시서를 쓰고 `herdr-delegate.sh review …`, 이어서 `herdr-wait.sh review`를 백그라운드로 건다.
6. `review.md`의 차단 항목을 확인한다. 있으면 수정 지시서(`<task-id>/fix-N/brief.md`)를 만들어 2번으로 돌아가고, 수정 뒤에는 다시 리뷰한다.
   - **초기 구현 1회 + 후속 수정 최대 2회**다. 후속 수정에는 리뷰 차단 사항과 main 재검증 실패가 모두 포함된다.
   - 2회를 넘으면 자동으로 더 시도하지 않고 남은 문제를 사용자에게 보고한다.
7. main이 AC를 직접 재검증한다(7절 범위, 같은 트리에서 impl·review가 Gradle을 돌리지 않을 때). impl의 필터 테스트 통과는 최종 AC가 아니다. Docker 미기동·포트 충돌·Testcontainers 실패 같은 **환경 문제는 impl 결함과 구분**해 보고하며 수정 횟수에 세지 않는다.
8. 최종 AC가 통과하면 다음 순서로 마무리한다.
   1. 로드맵 항목에 대응하는 task면 해당 체크박스만 갱신한다(대응 항목이 없으면 생략).
   2. 그 task의 파일과 로드맵만 스테이징하고(`git add -A` 금지) `git diff --cached --stat`으로 허용한 변경만 담겼는지 확인한다.
   3. 커밋이 허용된 경우에만 커밋한다. 허용되지 않았다면 로드맵 변경도 미커밋으로 두고 그 사실을 보고한다.
   4. `git status`로 남은 변경을 확인하고 보고한다.
9. 실행 범위(2절)가 다중 task일 때만 다음 task로 간다. 단계의 완료 기준을 넘는 task는 시작하지 않는다.

## 5. 지시서 템플릿

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
- 형식: 첫 줄 `# <task-id> 보고`, 마지막 줄 `결과: 완료` 또는 `결과: 실패`
- 포함: 변경 요약, 실행한 검사와 결과(실패는 그대로), 미해결 사항
```

### 리뷰 지시서

대상 diff 범위(`git diff HEAD -- 경로`와 신규 파일), 기준 문서([backend-review.md](backend-review.md) 또는 [frontend-review.md](frontend-review.md)), 출력 형식을 적고 **코드는 수정하지 말고 읽기만** 하도록 명시한다. diff만으로는 보이지 않는 문제(호출자와의 계약, 이벤트 소비자, 기존 테스트와의 충돌)를 위해 **인접 파일과 ADR을 읽는 것은 허용**한다. 결과는 `review.md`다.

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
