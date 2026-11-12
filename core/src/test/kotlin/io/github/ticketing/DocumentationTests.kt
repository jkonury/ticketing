package io.github.ticketing

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * 모듈 의존 다이어그램을 `docs/modulith/`에 PlantUML로 생성한다.
 * Gradle 테스트의 작업 디렉터리는 `core/`이므로 저장소 루트의 docs를 `../docs`로 가리킨다.
 * 기본 `test`에서는 제외되며, 모듈 의존을 바꾼 뒤 `./gradlew :core:modulithDocs`로 실행하고 바뀐 `.puml`을 함께 커밋한다.
 */
@Tag("docs")
class DocumentationTests {

    @Test
    fun `모듈 의존 다이어그램을 생성한다`() {
        val modules = ApplicationModules.of(TicketingApplication::class.java)
        Documenter(modules, Documenter.Options.defaults().withOutputFolder("../docs/modulith"))
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
    }
}
