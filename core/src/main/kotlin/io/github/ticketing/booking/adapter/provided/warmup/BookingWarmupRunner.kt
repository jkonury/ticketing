package io.github.ticketing.booking.adapter.provided.warmup

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_MAX_SECONDS = 30L
private const val MAX_LOCAL_ITERATIONS = 100000
private const val MAX_ADAPTER_ITERATIONS = 1000

@ConfigurationProperties("ticketing.startup-warmup")
internal data class BookingWarmupProperties(
    val enabled: Boolean = false,
    val maxDuration: Duration = Duration.ofSeconds(DEFAULT_MAX_SECONDS),
    val localIterations: Int = 10000,
    val adapterIterations: Int = 200,
) {
    init {
        require(!maxDuration.isNegative && !maxDuration.isZero && maxDuration <= Duration.ofMinutes(2))
        require(localIterations in 0..MAX_LOCAL_ITERATIONS && adapterIterations in 0..MAX_ADAPTER_ITERATIONS)
    }
}

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
@EnableConfigurationProperties(BookingWarmupProperties::class)
internal class BookingWarmupRunner(private val work: BookingWarmupWork, private val properties: BookingWarmupProperties) :
    ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun shouldStop(deadline: Long): Boolean = Thread.currentThread().isInterrupted || System.nanoTime() >= deadline

    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        val deadline = started + properties.maxDuration.toNanos()
        val local = AtomicInteger()
        val attempts = AtomicInteger()
        val wins = AtomicInteger()
        val counters = List(2) { WarmupIoCounts() }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val futures = (0 until 2).map { worker ->
            executor.submit {
                val counts = counters[worker]
                WarmupIo.measure(counts) {
                    for (iteration in worker until properties.localIterations step 2) {
                        if (shouldStop(deadline)) return@measure
                        work.localIteration()
                        local.incrementAndGet()
                    }
                    for (index in worker until properties.adapterIterations step 2) {
                        if (shouldStop(deadline)) break
                        if (work.exercise(index % 20 != 0)) wins.incrementAndGet()
                        attempts.incrementAndGet()
                        TimeUnit.MILLISECONDS.sleep(50)
                    }
                }
            }
        }
        var outcome = "completed"
        try {
            futures.forEach { it.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
            if (System.nanoTime() >= deadline) outcome = "timeout"
        } catch (_: TimeoutException) {
            outcome = "timeout"
        } catch (exception: ExecutionException) {
            outcome = "failed"
            logger.warn("내부 예열 실패, 예열을 중단하고 기동을 계속합니다.", exception.cause)
        } catch (_: InterruptedException) {
            outcome = "interrupted"
            Thread.currentThread().interrupt()
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            logger.info(
                "startup-warmup outcome={} elapsedMs={} local={} attempts={} winners={} dbStatements={} redisCommands={}",
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                local.get(),
                attempts.get(),
                wins.get(),
                counters.sumOf { it.dbStatements.get() },
                counters.sumOf { it.redisCommands.get() },
            )
        }
    }
}
