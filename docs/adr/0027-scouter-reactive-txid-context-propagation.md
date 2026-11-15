# 0027. Scouter WebFlux txid 전파를 context-propagation으로 교체한 패치 에이전트

## 상태

수락 (2026-11-15). 개발·부하 측정의 Scouter 구성에 대한 결정이며 앱 코드와 `application.yml`은 바꾸지 않는다.

## 맥락

Gateway·realtime(WebFlux)에서 HTTP XLog를 받으려면 Scouter `_hook_reactive_enabled=true`가 필요하다. 이 훅은 요청 진입점 계측과 함께
`Hooks.onEachOperator` lift(`TxidLifter`)를 등록해 연산자마다 txid ThreadLocal을 이어 준다.
코루틴 훅을 끄고 SSE 경로를 XLog에서 제외한 구성(F)에서도 queue-surge 앱 CPU가 훅 OFF 대비 +55.5%였다
([비교](../performance/scouter-gatling-comparison.md#후속-실험-txid-전파-방식-교체-패치-에이전트)).

JFR과 Reactor 3.8.8 소스로 확인한 원인은 Scouter 코드 자체가 아니었다(Scouter·lift 프레임 자체 샘플은 수 %).
두 앱은 `spring.reactor.context-propagation: auto`를 쓰는데, 이 상태에서 `Operators.LiftFunction.liftScannable`은
`onEachOperator`로 lift되는 **모든 연산자**의 하위 subscriber를 `ContextWriteRestoringThreadLocalsSubscriber`로 감싼다.
Scouter lifter가 subscriber를 그대로 돌려주는 경우(트랜잭션 컨텍스트가 없는 SSE 포함)에도 감싸므로, 신호마다 연산자 수만큼
Observation·OTel·SecurityContext ThreadLocal 복원이 반복된다.

## 검토한 대안

- 훅 OFF(`_hook_reactive_enabled=false`): 비용은 OFF와 같지만 WebFlux HTTP XLog·TPS·응답시간 수집이 중단된다.
- 설정만 조정(`profile_reactor_checkpoint_search_depth=0` 등): lift 등록 자체가 남아 원인을 건드리지 못한다.
- `TxidLifter` 경량화(`lift_light`, 체크포인트 탐색·Context 재할당·scannable 추적 제거): queue-surge 3회에서 F 대비 CPU -2.8%로 효과가 없었다.
- 앱의 자동 컨텍스트 전파 끄기: 분산 추적의 로그·trace 상관관계가 깨진다. 관측을 위해 앱 설정을 바꾸는 방향이라 택하지 않았다.

## 결정

- Scouter 2.21.3 소스에 `_hook_reactive_txid_propagation` 설정(`lift` 기본 / `lift_light` / `context_propagation`)을 추가한 패치 에이전트를 쓴다.
  패치는 [`deploy/scouter/patches/scouter-2.21.3-reactive-txid-propagation.patch`](../../deploy/scouter/patches/scouter-2.21.3-reactive-txid-propagation.patch)에 둔다.
- Gateway·realtime 설정은 `context_propagation`을 쓴다. 이 모드는 lift를 등록하지 않고 Micrometer `ContextRegistry`에
  `ThreadLocalAccessor`(키 `TraceContext.class`, 값 설정 시 txid ThreadLocal 설정)를 등록한다. 진입점이 Reactor Context에 넣는 키와 같아
  앱의 자동 전파가 스레드 경계에서 txid를 복원한다. 등록에 실패하면 `lift`로 되돌아간다.
- 공식 에이전트는 이 키를 무시하므로 설정 파일은 공식·패치 에이전트 양쪽에서 기동된다. 패치 에이전트는 `SCOUTER_AGENT_DIR`로 선택한다.
- 운영 배포에 적용하는 결정은 아니다.

## 결과

- queue-surge 3회 중앙값: 앱 CPU 851.9 CPU초로 OFF 851.8과 같고, F 대비 CPU -35.7%·GC -62.1%·측정 시간 -23.8%. HTTP XLog와 Filter 메서드 스텝 수집은 사용자가 수동 확인했다.
- txid는 스케줄러 경계와 `handle`·`tap` 등 자동 전파 지점에서만 복원된다. Netty 이벤트 루프에서 바로 발생하는 신호에서는 하위 스텝이
  덜 연결될 수 있다. Reactor 체크포인트 프로파일과 scannable 덤프는 쓸 수 없다. 앱이 자동 전파를 끄면 스레드 간 txid 전파가 사라진다.
- 공식 배포본이 아닌 에이전트를 직접 빌드해 유지한다. Scouter 버전을 올릴 때 패치를 다시 적용하고 같은 비교를 반복한다.
