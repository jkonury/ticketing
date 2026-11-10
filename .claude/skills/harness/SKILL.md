---
name: harness
description: |
  대규모 구현 작업을 phase/step으로 나누고, 각 step을 독립 세션으로 실행할 지시서와 index.json을 생성한다.
  "구현 계획을 step으로 나눠줘", "harness로 진행"처럼 여러 세션에 걸친 단계적 구현을 요청하거나 phases/ 파일을 작성·수정할 때 사용한다.
---

# Harness 워크플로우

각 step은 자기완결적 지시서(`step{NN}-{name}.md`)로 `scripts/execute.py`가 순차 실행한다. 아래 A → E를 순서대로 진행한다.

## A. 탐색

`docs/` 하위 문서(PRD, ARCHITECTURE, ADR 등)로 기획·아키텍처·설계 의도를 파악한다.
문서가 많으면 Explore 에이전트를 병렬로 쓰되, 원문이 아니라 **요약만 반환**하게 지시한다.
필요한 설계 문서가 없으면 `docs/templates/`의 템플릿을 기반으로, 템플릿 문서도 없으면 사용자와 논의해 `docs/`에 먼저 작성한다.

## B. 논의

구현을 위해 구체화하거나 결정해야 할 기술적 사항이 있으면 사용자에게 제시하고 논의한다.
결정되지 않은 상태에서 step을 작성하지 않는다.

## C. Step 설계

사용자가 구현 계획 작성을 지시하면 여러 step으로 나눈 초안을 작성하고 피드백을 요청한다.

**설계 원칙:**

1. **Scope 최소화** — 하나의 step에서 하나의 레이어 또는 모듈만 다룬다.
2. **자기완결성** — 각 step은 독립된 세션에서 실행된다. 필요한 정보를 파일 안에 담고, 문서 전문 읽기 대신 해당 step에 필요한 제약(디렉토리 구조, 기술 스택 등)을 지시서에 옮겨 적는다.
3. **최소 참조** — 이번 step이 의존하는 이전 step 생성 파일 경로만 명시한다. 문서는 꼭 필요할 때 `docs/X.md § 섹션명` 단위로 지정한다. 루트의 `CLAUDE.md`·`AGENTS.md`(와 그 `@import` 대상)는 step 세션이 저장소 루트에서 시작할 때 자동으로 컨텍스트에 로드되므로 "읽어야 할 파일"에 넣지 않는다. 이유: 같은 내용을 한 번 더 읽어 매 턴 중복 토큰이 된다. 단, step이 그 파일을 **수정**해야 하면 작업 절에 대상으로 적는다.
4. **시그니처 수준 지시** — 함수/클래스 인터페이스만 제시하고 구현은 에이전트 재량에 맡긴다. 단, 핵심 규칙(멱등성, 보안, 데이터 무결성)은 명시한다.
5. **AC는 실행 가능한 커맨드** — 프로젝트의 실제 검증 커맨드(`npm test`, `python -m pytest`, `./gradlew test` 등)를 넣는다. 출력이 재시도 컨텍스트로 전달되므로 조용한 옵션을 쓴다 (`pytest -q --tb=short`, `npm test --silent` 등).
6. **주의사항은 구체적으로** — "조심해라" 대신 "X를 하지 마라. 이유: Y" 형식.
7. **네이밍** — step name은 kebab-case slug (`project-setup`, `api-layer`, `auth-flow`).
8. **모델·추론 강도 선택** — 단순한 step(설정, 보일러플레이트, 문서 동기화, 매핑표대로의 이동)은 `model`에 `"sonnet"`이나 `"haiku"`를, `reasoning_effort`에 `"low"`나 `"medium"`을 지정해 비용을 줄인다. 설계 판단이 많은 step(경계 재구성, 메시징·멱등성, 빌드 구조 변경)만 `"high"`를 쓴다. 생략하면 CLI 설정에 따라 달라지므로 step마다 명시한다. Codex 실행기(`execute-codex.py`)는 별칭을 Codex 모델로 바꾸는데, `sonnet`이 기본 모델과 같은 모델로 매핑되면 비용이 줄지 않는다. 저장소의 `MODEL_ALIASES`를 확인하고, 저가 모델은 `haiku` 별칭이나 모델명을 직접 쓴다.
9. **초소형 step은 합치기** — 세션마다 기본 컨텍스트(약 7k 토큰)를 새로 캐시에 쓴다. 파일 1~2개짜리 작업은 같은 레이어의 인접 step에 합치고, AC를 여러 개 두어 검증 단위를 유지한다.
10. **반복 규칙은 복사하지 않고 참조** — 코딩·테스트 규칙처럼 `AGENTS.md`(또는 하위 디렉터리의 지침 파일)에 이미 있는 내용은 지시서에 요약 복사하지 말고 "X를 따른다" 한 줄로 쓴다. 도구 제한은 execute.py가 `--tools`로 강제하므로 금지사항에 적지 않는다. 이유: 지시서는 매 턴 cache 읽기로 반복되고, 사본은 원문과 어긋난다.
11. **큰 문서는 줄 범위로 지정** — 수십 KB 문서를 가리킬 때는 `docs/X.md:120-180 § 섹션명`처럼 줄 범위를 적는다. 통째로 읽으면 약 2만 토큰이 컨텍스트에 남아 이후 모든 턴에 반복된다. 단, 같은 phase의 앞선 step이 수정하는 문서는 줄 번호가 밀리므로 줄 범위 대신 `§ 섹션명`만 적고 `rg -n`으로 찾게 한다.
12. **summary는 2줄·200자 이내** — 다음 step의 `[CONTEXT]`로 전달된다(직전 step만 전문, 이전 step은 120자로 잘림). 상세 결과는 산출물 파일에 남기고 summary에는 경로와 핵심 결론만 쓴다.
13. **무거운 step은 성격별로 나눈다** — 빌드 구조 변경, 대량 파일 이동, 테스트 기반 재구성, 런타임 설정은 서로 다른 step에 둔다. 모듈 3개 이상을 동시에 고치거나 검증·수정 반복이 수십 번 예상되는 step도 나눈다. 이유: 세션 비용은 호출 수 × 누적 문맥으로 늘어나므로, 호출 77회짜리 한 세션보다 25회짜리 세 세션이 싸다(세션마다 기본 문맥을 다시 써도 그렇다). 나눈 뒤에는 각 step이 끝난 과도기 상태가 다른 step의 금지사항과 충돌하지 않는지 확인한다. 예: "in-process 의존 유지"와 "다른 서비스 schema 접근 금지"가 한 step에 함께 있으면 순서를 바꿔 해소한다.
14. **기계적 변경은 매핑표와 일괄 스크립트로** — 파일·패키지 이동이나 import 치환은 지시서에 `원본 → 대상` 매핑표를 적고 "스크립트 한 번으로 `git mv`/`sed` 처리, 파일별 편집 금지"를 금지사항에 넣는다. 이유: 에이전트가 스크립트를 직접 설계하느라 탐색이 늘고, 파일마다 처리하면 도구 호출이 그만큼 늘어난다. 파일 내용은 heredoc이 아니라 Write/Edit로 쓰게 한다. 명령 정책 훅이 명령 전체 문자열을 검사하면 본문에 든 금지 문자열 때문에 명령이 차단된다.
15. **검증 출력은 요약 스크립트로** — 지시서의 검증 절차에는 실패만 출력하는 저장소 스크립트(예: `run-tests.sh`, `check-summary.sh`)를 적는다. 그런 스크립트가 없고 빌드 출력이 길면, phase를 시작하기 전에 만드는 것을 사용자에게 제안한다. 이유: 긴 도구 출력은 그 세션의 이후 모든 호출에 문맥으로 다시 실린다.
16. **구조 목표는 AC로 검사한다** — "X 참조가 없다", "디렉터리가 삭제됐다" 같은 목표는 `! rg -n '<패턴>' <경로>`, `test ! -e <경로>`처럼 셸 커맨드로 AC에 넣는다(AC는 셸로 실행된다). 지시서를 쓴 뒤 현재 트리에서 실행해 실패하는지 확인한다. 작업 전부터 통과하는 AC는 아무것도 검증하지 않는다.

## D. 파일 생성

사용자가 승인하면 아래 파일들을 생성한다. 타임스탬프(`created_at`, `completed_at` 등)와 `summary`는 실행 중에 기록되므로 생성 시 넣지 않는다.

### D-1. `phases/index.json`

```json
{
  "phases": [
    { "dir": "0-mvp" },
    { "dir": "1-search", "depends_on": ["0-mvp"] }
  ]
}
```

- 이미 존재하면 `phases` 배열에 새 항목 추가.
- `depends_on` (선택): 이 phase가 전제로 하는 이전 phase 코드. step 지시서가 다른 phase의 산출물을 참조하면 반드시 적는다. `execute.py`가 시작 전에 선행 phase의 step이 현재 브랜치에서 모두 `completed`인지 검사하고, 아니면 실행하지 않는다.
- `status`는 두지 않는다. 이유: worktree마다 사본이 갈라지고, 여러 phase 브랜치를 merge할 때 인접 줄 수정으로 충돌한다. 완료 여부는 phase별 `index.json`의 step 상태로 판단한다.
- 서로 의존하지 않는 phase는 병렬로 실행할 수 있다. 같은 패키지를 건드리면 지시서에 "새 파일만 만든다" 같은 충돌 회피 규칙을 넣는다.

### D-2. `phases/{phase-name}/index.json`

```json
{
  "project": "<프로젝트명>",
  "phase": "<phase-name>",
  "steps": [
    { "step": 0, "name": "project-setup", "status": "pending", "ac": ["uv run pytest -q tests/setup"], "model": "sonnet", "reasoning_effort": "low" },
    { "step": 1, "name": "core-types",    "status": "pending", "ac": ["uv run pytest -q tests/core"], "reasoning_effort": "high" }
  ]
}
```

- `step`: 0부터 시작하는 순번. `status`: 초기값 모두 `"pending"`.
- `ac`: 저장소 루트에서 순서대로 실행되는 셸 커맨드 목록. 하나라도 실패하면 step 실패. 생략하면 에이전트 보고만으로 완료 판정한다.
- `model` (선택): 해당 step 세션의 모델. 생략하면 CLI 기본값.
- `reasoning_effort` (선택): 해당 step 세션의 추론 강도. Claude는 `low`·`medium`·`high`·`xhigh`·`max`(`--effort`로 전달), Codex는 여기에 `ultra`까지 쓸 수 있다. 우선순위는 실행 옵션 `--reasoning-effort` → step 값 → CLI 기본 설정이다. 잘못된 값은 실행 전에 거부된다.

### D-3. `phases/{phase-name}/step{NN}-{name}.md` (step마다 1개)

[templates/step.md](templates/step.md)를 복사해 `{…}` 자리를 채운다.

- 파일명 예시: `step00-project-setup.md` (zero-padding: 정렬 순서가 index.json과 일치하도록)
- Acceptance Criteria 커맨드는 D-2의 `ac`와 반드시 일치시킨다.

## E. 실행 안내

실행은 이 세션에서 하지 않는다. 파일 생성 후 아래를 진행한다.

1. **phase 파일 커밋** — 사용자 확인 후, 생성한 `phases/` 파일만 스테이징해 현재 브랜치에 커밋한다 (예: `docs: {phase} harness 계획 추가`). 관련 없는 변경은 포함하지 않는다.
2. **실행 방법 안내** — 새 터미널에서 worktree 세션을 열고 실행 스킬을 호출하도록 안내한다. `{base}`는 phase 파일을 커밋한 브랜치(`git branch --show-current`)이고, 선행 phase가 있으면 그 phase가 merge된 브랜치다.
   ```sh
   git worktree add -b {phase} ../{phase} {base}   # wt를 쓴다면: wt switch -c {phase} --base {base}
   ```
   ```text
   /harness-run {phase} {base}
   ```
   - `--base`를 반드시 붙인다. 이유: 생략하면 기본 브랜치(main)에서 갈라져 phase 파일과 `scripts/`가 없는 worktree가 된다.
   - `depends_on`이 있는 phase는 선행 phase를 `{base}`에 merge한 **뒤에** worktree를 만든다. 의존 관계가 없는 phase끼리는 동시에 실행해도 된다.
3. **완료 후 정리 안내** — phase 브랜치를 리뷰·merge한 뒤 원래 체크아웃에서 정리하도록 안내한다.
   - 로그(`phases/{phase}/logs/`, gitignore 대상)는 worktree 안에 쌓이므로 worktree 삭제 시 함께 사라진다. 측정 목적 phase라면 worktree 삭제 전에 메타데이터 JSON만 내보낸다. `.log`(프롬프트 원문)는 보관하지 않는다.
     ```sh
     rsync -a --include='*/' --include='*.json' --exclude='*' phases/{phase}/logs/ ~/harness-metrics/{phase}/
     ```
   - 계획 파일은 git 히스토리에 맡기고 삭제한다. 이유: step 지시서는 작성 시점의 코드를 전제로 한 일회성 프롬프트라 남겨 두면 이후 탐색(A)에서 낡은 맥락이 된다.
     ```sh
     python3 scripts/prune_phase.py {phase} --dry-run   # 검사만
     python3 scripts/prune_phase.py {phase} --tag       # phase/{phase} 태그 후 삭제·커밋
     ```
     모든 step이 `completed`이고 phase 경로에 미커밋 변경이 없어야 하며, 로그가 남아 있으면 `--delete-logs`를 명시해야 진행한다.
   - 오래 유지할 설계 결정은 `docs/`(ADR 등)로 옮긴다. phase 번호는 삭제 후에도 재사용하지 않고 계속 증가시킨다.

실행 중 동작(재시도, 커밋, 로그)에 대한 질문은 [references/execute.md](references/execute.md)를 참고해 답한다.
