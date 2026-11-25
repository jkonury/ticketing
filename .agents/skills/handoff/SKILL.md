---
name: handoff
description: 세션 인계 문서를 .handoff/에 작성하고 문서 검사까지 실행한다. 사용자가 세션 인계·핸드오프를 요청하거나 컨텍스트가 길어 새 세션으로 넘길 때 사용.
disable-model-invocation: true
---

세션 인계 문서를 작성한다.

1. `docs/templates/session-handoff.md`를 읽는다. 주석에 적힌 원칙을 따른다.
2. 현재 상태를 수집한다.
   - `git branch --show-current`, `git log --oneline -5`, `git status --short`
   - `.handoff/`의 가장 최근 문서가 있으면 "이전 인계 문서"로 연결한다.
3. `.handoff/YYYY-MM-DD-<주제>.md`(오늘 날짜, 주제는 영어 kebab-case 또는 한글)로 쓴다.
   - 작성자는 지금 실행 중인 도구(`Claude Code` 또는 `Codex`).
   - 저장소를 봐서는 알 수 없는 것만 적고, commit된 내용은 해시로만 가리킨다.
   - 해당 없는 절은 지우지 않고 "없음"으로 적는다.
   - 확인하지 못한 가정은 6절에 따로 적는다.
   - 사용자와 합의하지 않은 내용을 4절(결정)에 넣지 않는다.
4. `./scripts/agent/docs/check-docs.sh .handoff/<파일>`을 실행하고 실패하면 고친다.
5. 파일 경로와 한 줄 요약만 사용자에게 보고한다. `.handoff/`는 git 제외이므로 커밋하지 않는다.
