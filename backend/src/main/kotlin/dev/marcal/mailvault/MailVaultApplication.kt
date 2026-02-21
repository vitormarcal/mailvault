package dev.marcal.mailvault

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MailVaultApplication

fun main(args: Array<String>) {
	runApplication<MailVaultApplication>(*args)
}
