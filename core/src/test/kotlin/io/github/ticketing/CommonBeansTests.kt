package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import kotlin.test.assertEquals

class CommonBeansTests(private val beanFactory: ListableBeanFactory) : IntegrationTestSupport() {

    @Test
    fun `ApiExceptionHandler 빈은 스캔과 auto-configuration이 겹쳐도 하나만 등록된다`() {
        val names = beanFactory.getBeanNamesForType(ApiExceptionHandler::class.java).toList()
        assertEquals(1, names.size, "등록된 빈 이름: $names")
    }
}
