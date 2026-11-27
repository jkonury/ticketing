---
name: grill-with-docs
description: 계획·설계·결정을 집요하게 인터뷰해 공유된 이해에 도달하고, 그 과정에서 합의된 용어를 용어사전에, 설계 결정을 ADR에 기록한다. 사용자가 계획·설계를 검증받고 싶다거나 grill 계열 표현을 쓸 때 사용.
disable-model-invocation: true
---

계획이나 설계를 인터뷰로 다듬고, 합의된 것을 문서에 바로 남긴다.

출처: [mattpocock/skills](https://github.com/mattpocock/skills)의 `grill-with-docs`, `grilling`, `domain-modeling`(commit `6f7f783`)을 합쳐 이 저장소에 맞게 고쳤다. 이 스킬 디렉터리를 `SKILL_DIR`이라 부른다.

1. `SKILL_DIR/references/grilling.md`를 읽고 그 방식대로 인터뷰한다. 질문은 라운드 단위로 묶고, 라운드마다 사용자 답을 기다린다.
2. `SKILL_DIR/references/domain-modeling.md`를 읽고 인터뷰 중에 용어를 점검하고 기록한다.
3. 인터뷰 전에 [docs/roadmap.md](../../../docs/roadmap.md)에서 현재 단계와 관련 항목을, [docs/architecture.md](../../../docs/architecture.md)와 관련 ADR에서 이미 정해진 것을 확인한다. 이미 합의된 결정은 다시 묻지 않고, 어긋나는 제안이 나오면 그 사실을 먼저 알린다.
4. 사용자가 합의를 확인하기 전에는 계획을 실행하지 않는다.

## 이 저장소의 기록 위치

| 기록 | 위치 | 쓰는 때 |
| --- | --- | --- |
| 용어 | [docs/glossary.md](../../../docs/glossary.md) | 용어가 합의되는 즉시 |
| 설계 결정 | [ADR](../../../docs/adr/README.md)(템플릿 [docs/templates/adr.md](../../../docs/templates/adr.md)) | 아래 세 조건을 모두 만족하고 사용자가 동의할 때 |
| 설계가 바뀌는 결과 | [docs/architecture.md](../../../docs/architecture.md) | ADR을 쓸 때 같은 변경에서 |

- 설계를 바꾸는 결정은 사용자와 합의한 뒤에만 ADR을 쓴다([AGENTS.md 3절](../../../AGENTS.md#3-작업-방식)). 인터뷰 중 나온 의견을 ADR 상태 `합의`로 쓰지 않는다. 합의 전에는 `제안`이다.
- 문서를 고친 뒤 `./scripts/agent/docs/check-docs.sh <경로>`로 링크와 표기를 확인한다.
