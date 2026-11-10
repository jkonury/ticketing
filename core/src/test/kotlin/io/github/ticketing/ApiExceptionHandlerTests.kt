package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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

    @Test
    fun `낙관적 락 충돌은 409 CONCURRENT_MODIFICATION으로 변환된다`() {
        mockMvc.perform(get("/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
            .andExpect(jsonPath("$.status").value(409))
    }
}
