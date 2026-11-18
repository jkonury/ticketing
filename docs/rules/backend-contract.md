# contract 작성 지침

- `contract`의 이벤트 클래스는 논리적 소유 모듈의 기존 패키지를 유지하고 Kotlin·JDK 타입만 사용한다. JSON 하위 호환을 유지하며 깨지는 변경은 새 이벤트 타입으로 도입한다. 계약 변경은 공유 골든 파일과 `contract`·`core`의 직렬화 테스트로 확인한다. [ADR 0042](../adr/0042-contract-module-keeps-packages-and-json-compat-rules.md)

이벤트 필드의 JSON 표현과 호환 조건은 [이벤트 카탈로그](../architecture/events.md#1-공통-규칙), 직렬화 검증 범위는 [테스트 지침](backend-testing.md#이벤트-계약)을 따른다.
