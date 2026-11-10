# 0010. 모듈 간은 Modulith 이벤트, 앱 간은 Redis Streams

- 상태: 수락
- 일자: 2026-11-10

## 맥락

코어의 모듈끼리, 그리고 코어와 실시간 앱 사이에 통신 수단이 필요하다. Modulith의 모듈 경계는 추후 MSA의 서비스 경계가 된다.

## 검토한 대안

모듈 간:

- Spring Modulith 이벤트 (`ApplicationModuleListener` + 이벤트 발행 레지스트리)
- 모듈의 공개 API 직접 호출

코어 → 실시간 앱(좌석 상태 변경 전달):

- (a) Redis Pub/Sub 또는 Streams
- (b) 처음부터 Kafka
- (c) HTTP 호출

## 결정

- 모듈 간: **Modulith 이벤트**
- 앱 간: **Redis Streams**

## 결과

- 이미 Redis를 쓰고 있어 인프라가 늘지 않는다.
- 모듈 간 호출을 이벤트로 제한해 두었으므로, MSA 전환 시 Modulith의 이벤트 외부화 기능으로 전송 수단만 Kafka로 바꾸면 된다.
- 이벤트는 다시 전달될 수 있으므로 리스너는 멱등해야 한다.
- 이벤트 페이로드는 외부화를 고려해 ID와 단순 값만 담는다.
- Kafka 도입은 MSA 전환 시점으로 미룬다.
