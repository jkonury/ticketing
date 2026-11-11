package io.github.ticketing.fakepg.payment.service

class IdempotencyKeyReusedException(key: String) : RuntimeException("같은 Idempotency-Key에 다른 요청 본문이 들어왔다: $key")

class TransactionNotFoundException(transactionId: String) : RuntimeException("거래를 찾을 수 없다: $transactionId")

class TransactionNotRefundableException(transactionId: String) : RuntimeException("승인된 거래만 환불할 수 있다: $transactionId")
