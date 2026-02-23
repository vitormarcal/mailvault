package dev.marcal.mailvault

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MailVaultApplication

fun main(args: Array<String>) {
	runApplication<MailVaultApplication>(*args)
}
