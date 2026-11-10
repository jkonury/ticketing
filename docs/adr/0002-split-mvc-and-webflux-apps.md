# 0002. MVC 코어와 WebFlux 실시간 앱을 배포 단위로 분리

- 상태: 수락
- 일자: 2026-11-10

## 맥락

WebMVC + Virtual Thread와 WebFlux + Coroutines를 모두 쓰려 한다. 한 JVM 앱에는 웹 스택이 하나만 올라가므로 한 애플리케이션 안에서 둘을 섞을 수 없다.

## 검토한 대안

- (a) 코어 앱(Modulith, MVC) + 실시간 앱(WebFlux) + Gateway, 3개 배포 단위로 시작
- (b) 코어 Modulith 하나로 시작하고 SSE도 MVC의 `SseEmitter` + Virtual Thread로 처리, WebFlux는 Gateway에서만 사용
- (c) 같은 기능을 두 스택으로 각각 구현해 비교

## 결정

**(a)** 로 한다. 코어는 Modulith로 모듈 경계를 다듬고, 실시간 앱은 처음부터 분리한다.

## 결과

- 처음부터 배포 단위가 여러 개이므로 로컬 실행과 설정이 (b)보다 복잡하다.
- 실시간 앱이 이미 분리되어 있어 MSA 전환 시에는 코어의 모듈만 떼어내면 된다.
- 장기 연결(SSE)이 코어의 자원을 점유하지 않는다.
- (c)의 스택 비교는 하지 않는다.
