package io.github.ticketing.payment.adapter.provided.warmup

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFAULT_MAX_SECONDS = 30L
private const val MAX_ADAPTER_ITERATIONS = 1000
private const val PROBE_INTERVAL_MILLIS = 50L

@ConfigurationProperties("ticketing.startup-warmup")
internal data class PaymentWarmupProperties(
    val maxDuration: Duration = Duration.ofSeconds(DEFAULT_MAX_SECONDS),
    val adapterIterations: Int = 200,
) {
    init {
        require(!maxDuration.isNegative && !maxDuration.isZero && maxDuration <= Duration.ofMinutes(2))
        require(adapterIterations in 0..MAX_ADAPTER_ITERATIONS)
    }
}

@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
@EnableConfigurationProperties(PaymentWarmupProperties::class)
internal class PaymentWarmupRunner(private val work: PaymentWarmupWork, private val properties: PaymentWarmupProperties) :
    ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        val deadline = started + properties.maxDuration.toNanos()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val future = executor.submit {
            repeat(properties.adapterIterations) { iteration ->
                if (iteration > 0) TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MILLIS)
                if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return@submit
                work.run()
            }
        }

        var outcome = "completed"
        try {
            future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            if (System.nanoTime() >= deadline) outcome = "timeout"
        } catch (_: TimeoutException) {
            outcome = "timeout"
        } catch (exception: ExecutionException) {
            outcome = "failed"
            logger.warn("결제 예열 실패, 예열을 중단하고 기동을 계속합니다.", exception.cause)
        } catch (_: InterruptedException) {
            outcome = "interrupted"
            Thread.currentThread().interrupt()
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            logger.info(
                "payment-warmup outcome={} elapsedMs={} pgProbes={}",
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                work.pgProbes,
            )
        }
    }
}
