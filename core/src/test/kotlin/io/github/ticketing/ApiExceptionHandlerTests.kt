package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.result.MockMvcResultHandlers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class ApiExceptionHandlerTests {

    @RestController
    class ThrowingController {
        @GetMapping("/conflict")
        fun conflict(): String = throw ObjectOptimisticLockingFailureException(Any::class.java, 1L)
    }

    private val mockMvc = MockMvcBuilders.standaloneSetup(ThrowingController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    val mockMvcTester = MockMvcTester.create(mockMvc)

    @Test
    fun `낙관적 락 충돌은 409 CONCURRENT_MODIFICATION으로 변환된다`() {
        mockMvcTester.get()
            .uri("/conflict")
            .exchange()
            .assertThat()
            .hasStatus(HttpStatus.CONFLICT)
            .apply(MockMvcResultHandlers.print())
            .bodyJson().apply {
                extractingPath("code").isEqualTo("CONCURRENT_MODIFICATION")
                extractingPath("status").isEqualTo(409)
            }
    }
}
