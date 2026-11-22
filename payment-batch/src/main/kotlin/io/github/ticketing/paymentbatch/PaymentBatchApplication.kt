package io.github.ticketing.paymentbatch

import io.github.ticketing.PaymentApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val application = SpringApplication(PaymentApplication::class.java)
    application.setAdditionalProfiles("batch")
    application.setWebApplicationType(WebApplicationType.NONE)
    val context = application.run(*args)
    exitProcess(SpringApplication.exit(context))
}
