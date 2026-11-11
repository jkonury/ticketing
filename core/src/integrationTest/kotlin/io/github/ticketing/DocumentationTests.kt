package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * 모듈 의존 다이어그램을 지정된 디렉터리에 PlantUML로 생성한다.
 */
class DocumentationTests {

    @Test
    fun `모듈 의존 다이어그램을 생성한다`() {
        val modules = ApplicationModules.of(TicketingApplication::class.java)
        Documenter(modules, Documenter.Options.defaults().withOutputFolder(requireNotNull(System.getProperty("modulith.output"))))
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
    }
}
