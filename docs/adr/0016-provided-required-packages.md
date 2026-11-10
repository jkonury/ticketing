# 0016. 헥사고날 패키지는 provided / required로 명명

- 상태: 수락
- 일자: 2026-11-10

## 맥락

헥사고날 모듈의 입력·출력 측 패키지명으로 정한 `in`, `out`이 Kotlin 키워드와 겹친다. 패키지 선언과 import에서 이스케이프를 피할 수 있는 이름이 필요하다.

## 검토한 대안

- (a) `in` / `out`을 유지하고 필요한 곳에서 백틱으로 이스케이프
- (b) `provided` / `required`로 변경

## 결정

**(b)** 로 한다. 애플리케이션이 제공하는 기능은 `provided`, 애플리케이션이 요구하는 외부 기능은 `required`로 구분한다.

- 입력 포트: `application.port.provided`
- 출력 포트: `application.port.required`
- 입력 어댑터: `adapter.provided.web`, `adapter.provided.event`
- 출력 어댑터: `adapter.required.jpa`, `adapter.required.jooq`, `adapter.required.redis`, `adapter.required.pg`

## 결과

- Kotlin 키워드를 패키지명으로 사용하지 않아 이스케이프가 필요 없다.
- 헥사고날 적용 범위와 의존 방향은 기존 결정을 유지한다.
