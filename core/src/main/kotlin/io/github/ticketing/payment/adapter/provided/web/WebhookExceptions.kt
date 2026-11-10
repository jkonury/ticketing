package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.DomainException
import org.springframework.http.HttpStatus

class InvalidWebhookSignatureException :
    DomainException("INVALID_WEBHOOK_SIGNATURE", HttpStatus.UNAUTHORIZED, "웹훅 서명이 올바르지 않습니다.")

class InvalidWebhookPayloadException :
    DomainException("INVALID_WEBHOOK_PAYLOAD", HttpStatus.BAD_REQUEST, "웹훅 본문을 해석할 수 없습니다.")
