package io.github.ticketing

import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.support.KafkaPaymentStub
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class KafkaRecoveryIntegrationTests {
    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `브로커 장애로 실패한 결제 요청 외부화는 복구 후 재기동에서 재발행되어 예매가 확정된다`(output: CapturedOutput) {
        val namespace = SharedContainers.newNamespace()
        TestKafkaContainer().use { kafka ->
            kafka.start()
            verifyRecovery(namespace, kafka)
        }

        // 결과가 실제 예매에 닿았다면 예매를 찾지 못했다는 경고가 없어야 한다
        assertThat(output.all).doesNotContain("결제 결과의 예매를 찾을 수 없다")
    }

    private fun verifyRecovery(namespace: TestNamespace, kafka: KafkaContainer) {
        val args = KafkaRecoveryTestSupport.arguments(namespace, kafka)
        val docker = DockerClientFactory.instance().client()
        lateinit var publicationId: UUID
        var bookingId = 0L
        KafkaRecoveryTestSupport.start(args + "--spring.kafka.listener.auto-startup=false").use { context ->
            assertThat(context.getBean(KafkaListenerEndpointRegistry::class.java).listenerContainers).allMatch { !it.isRunning }
            val jdbc = context.getBean(JdbcTemplate::class.java)
            bookingId = pendingBooking(context)
            docker.pauseContainerCmd(kafka.containerId).exec()
            try {
                // 브로커가 멈춘 동안 실제 결제 요청 유스케이스가 PaymentRequested를 발행한다
                context.getBean(RequestPaymentUseCase::class.java).request(MEMBER_ID, bookingId)
                await().atMost(Duration.ofSeconds(30)).untilAsserted {
                    val ids = jdbc.queryForList(
                        "select id from public.event_publication where event_type = ? and serialized_event like ? " +
                            "and completion_date is null and status = ?",
                        UUID::class.java,
                        PaymentRequested::class.java.name,
                        "%\"orderId\":$bookingId%",
                        "FAILED",
                    )
                    assertThat(ids).hasSize(1)
                    publicationId = requireNotNull(ids.single())
                }
            } finally {
                docker.unpauseContainerCmd(kafka.containerId).exec()
            }
        }
        // 재발행되는 요청을 대역이 소비하기 전에 응답을 지정해야 하므로 대역 빈이 만들어진 직후에 지정한다
        val approveBeforeRequest = ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
            context.beanFactory.addBeanPostProcessor(
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                        if (bean is KafkaPaymentStub) bean.respondToPayment(bookingId, KafkaPaymentStub.PaymentResponse.Succeeded())
                        return bean
                    }
                },
            )
        }
        KafkaRecoveryTestSupport.start(args, approveBeforeRequest).use { context ->
            assertThat(context.getBean(KafkaListenerEndpointRegistry::class.java).listenerContainers).anyMatch { it.isRunning }
            val jdbc = context.getBean(JdbcTemplate::class.java)
            val stub = context.getBean(KafkaPaymentStub::class.java)
            await().atMost(Duration.ofSeconds(60)).untilAsserted {
                assertThat(
                    jdbc.queryForObject(
                        "select count(*) from public.event_publication where id = ? and completion_date is not null",
                        Int::class.java,
                        publicationId,
                    ),
                ).isEqualTo(1)
            }

            // 재발행된 요청이 브로커를 거쳐 결제 대역(payment 앱 자리)에 한 번 도착한다
            await().atMost(Duration.ofSeconds(60)).untilAsserted { assertThat(stub.paymentRequestsOf(bookingId)).hasSize(1) }
            val request = stub.paymentRequestsOf(bookingId).single()
            assertThat(request.idempotencyKey).isEqualTo("booking-$bookingId")
            assertThat(request.amount).isEqualByComparingTo(BigDecimal(100000))

            // 대역이 승인한 결과를 booking 소비자가 받아 같은 예매를 확정하고 좌석을 판매 처리한다
            await().atMost(Duration.ofSeconds(60)).untilAsserted {
                assertThat(jdbc.queryForObject("select status from booking.booking where id = ?", String::class.java, bookingId))
                    .isEqualTo("CONFIRMED")
            }
            assertThat(jdbc.queryForObject("select count(*) from booking.sold_seat where booking_id = ?", Int::class.java, bookingId))
                .isEqualTo(1)
            stub.awaitResultsConsumedByBooking(context.getBean(KafkaTopics::class.java).paymentSucceeded, bookingId, 1)
        }
    }

    /** 실제 유스케이스로 회차를 등록하고 좌석 하나를 선점한 PENDING 예매를 만든다. */
    private fun pendingBooking(context: ConfigurableApplicationContext): Long {
        val scheduleId = System.nanoTime()
        context.getBean(RegisterScheduleSeatsUseCase::class.java).register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                "복구 공연",
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                listOf(RegisterScheduleSeatsCommand.SeatInfo(1, "R", "A구역 1번", BigDecimal(100000))),
            ),
        )
        val created = context.getBean(CreateBookingUseCase::class.java)
            .create(CreateBookingCommand(MEMBER_ID, UUID.randomUUID().toString(), scheduleId, listOf(1)))
        assertThat(created.booking.status).isEqualTo("PENDING")
        return created.booking.bookingId
    }

    private companion object {
        const val MEMBER_ID = 1L
    }
}

internal object KafkaRecoveryTestSupport {
    fun arguments(namespace: TestNamespace, kafka: KafkaContainer): Array<String> = (
        namespace.properties.map { (key, value) -> "--$key=$value" } + listOf(
            "--spring.config.import=",
            "--spring.profiles.active=recovery-test",
            "--spring.profiles.include=",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.kafka.producer.properties.max.block.ms=3000",
            "--spring.kafka.producer.properties.delivery.timeout.ms=5000",
            "--spring.kafka.producer.properties.request.timeout.ms=3000",
            "--spring.kafka.producer.properties.linger.ms=0",
            "--ticketing.startup-warmup.enabled=false",
            "--ticketing.jwt.secret=test-secret-test-secret-test-secret-0123",
            "--server.port=0",
        )
        ).toTypedArray()

    fun start(
        args: Array<String>,
        initializer: ApplicationContextInitializer<ConfigurableApplicationContext>? = null,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(TicketingApplication::class.java, CommonTestConfiguration::class.java).apply {
            if (initializer != null) initializers(initializer)
        }.run(*args).also { context ->
            assertThat(context.environment.activeProfiles).containsExactly("recovery-test")
            assertThat(context.environment.getProperty("ticketing.startup-warmup.enabled")).isEqualTo("false")
        }
}
