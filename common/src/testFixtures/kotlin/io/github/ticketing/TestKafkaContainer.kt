package io.github.ticketing

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.KafkaContainer

class TestKafkaContainer : KafkaContainer("apache/kafka:4.3.2") {
    init {
        // Testcontainers #11682: 시작 스크립트가 복사 중이면 실행에 실패할 수 있다.
        withCommand("sh", "-c", "while [ ! -f $STARTER_READY ]; do sleep 0.1; done; exec /tmp/testcontainers_start.sh")
    }

    override fun containerIsStarting(containerInfo: InspectContainerResponse) {
        super.containerIsStarting(containerInfo)
        copyFileToContainer(Transferable.of(""), STARTER_READY)
    }

    private companion object {
        const val STARTER_READY = "/tmp/testcontainers_start.ready"
    }
}
