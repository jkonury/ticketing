package io.github.ticketing.payment

/**
 * 내부 기동 예열이 payment 모듈의 PG 조회·웹훅 처리 경로 예열을 요청한다. 데이터를 담지 않으며 처리는 롤백되는 트랜잭션과
 * 부작용 없는 PG 조회로만 한다. 예열을 켜지 않으면 수신자가 없다.
 */
class PaymentWarmupRequested
