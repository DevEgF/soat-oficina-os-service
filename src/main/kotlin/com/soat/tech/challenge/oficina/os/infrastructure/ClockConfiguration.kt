package com.soat.tech.challenge.oficina.os.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfiguration {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
