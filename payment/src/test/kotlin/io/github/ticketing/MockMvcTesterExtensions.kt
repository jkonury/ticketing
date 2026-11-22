package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult

/** 응답 상태를 검증한 뒤 같은 결과를 돌려준다. 이후 본문이나 헤더를 꺼내 쓸 때 사용한다. */
fun MvcTestResult.expectStatus(status: HttpStatus): MvcTestResult = also { assertThat(it).hasStatus(status) }
