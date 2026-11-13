package com.soat.tech.challenge.oficina.os.bdd

import com.soat.tech.challenge.oficina.os.integration.IntegrationTestSupport
import io.cucumber.spring.CucumberContextConfiguration

/**
 * Spring context of the scenarios: the same configuration as the integration tests (same properties,
 * profile and containers), so the context cached by them is reused.
 */
@CucumberContextConfiguration
class CucumberSpringConfiguration : IntegrationTestSupport()
