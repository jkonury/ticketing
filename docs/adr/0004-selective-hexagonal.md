# 0004. 헥사고날은 핵심 모듈에만 적용

- 상태: 수락
- 일자: 2026-11-10

## 맥락

코어는 6개 모듈(`member`, `catalog`, `booking`, `payment`, `settlement`, `notification`)로 구성된다. 헥사고날 아키텍처를 어디까지 적용할지 정해야 한다.

## 검토한 대안

- (a) 모든 모듈에 동일하게 적용 (`domain` / `application` / `adapter`)
- (b) 핵심 모듈만 완전 적용, 단순 모듈은 계층형으로 간소화

## 결정

**(b)** 로 한다.

- 헥사고날: `booking`, `payment`, `settlement`
- 계층형: `member`, `catalog`, `notification`

## 결과

- 단순 CRUD 모듈에 포트와 매퍼를 만드는 비용을 피한다.
- 모듈마다 구조가 달라지므로, 경계와 의존 방향은 Modulith 검증 테스트와 ArchUnit으로 강제한다.
- 계층형 모듈이 복잡해지면 그때 헥사고날로 옮긴다.
