---
name: plan-next
description: 로드맵의 다음 항목을 검토하고 사용자가 승인할 실행 계획을 작성한다. Claude Code의 /plan-next와 Codex의 $plan-next에서 쓴다.
disable-model-invocation: true
---

1. 요청의 트랙과 항목을 로드맵에서 찾는다. 항목을 생략했으면 지정한 트랙의 첫 미완료 항목을 고른다. 선행 단계의 완료 기준과 관련 ADR을 확인한다.
2. 설계에 열린 결정은 [grill-with-docs](../grill-with-docs/SKILL.md)로 정리한다. 이미 합의된 결정은 재사용한다.
3. [실행 계획 지침](../../../docs/workflow/plans/README.md)에 따라 [템플릿](../../../docs/workflow/plans/template.md)으로 `draft`를 쓴다. task의 범위와 선행 관계, 공개 테스트 경계와 실행할 AC를 코드와 대조한다. 문서 검사를 실행한다.
4. 파일과 주요 결정, task 세분도·선행 관계를 사용자에게 보여 준다. 이 계획을 승인한다는 답을 받은 뒤 `approved`와 승인 시각을 기록한다. 승인 이후 변경된 범위·테스트 경계·AC는 다시 검토받는다.
5. [implement](../implement/SKILL.md)에 넘길 계획 경로를 알려 준다. 계획 파일 커밋 권한은 [실행 계획 지침](../../../docs/workflow/plans/README.md)을 따른다.
