package com.wmspro.tenant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WmsTenantServiceApplication

fun main(args: Array<String>) {
	runApplication<WmsTenantServiceApplication>(*args)
}
