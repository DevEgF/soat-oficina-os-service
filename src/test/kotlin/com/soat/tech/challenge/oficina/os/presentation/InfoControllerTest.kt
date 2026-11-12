package com.soat.tech.challenge.oficina.os.presentation

import com.soat.tech.challenge.oficina.os.infrastructure.AppEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import java.util.Properties

class InfoControllerTest {

    @Test
    fun `reports service environment and build version`() {
        val beans = StaticListableBeanFactory().apply {
            addBean("buildProperties", BuildProperties(Properties().apply { setProperty("version", "1.2.3") }))
        }

        val info = InfoController("os-service", AppEnvironment("hml"), beans.getBeanProvider(BuildProperties::class.java)).info()

        assertEquals(ServiceInfoResponse("os-service", "hml", "1.2.3"), info)
    }

    @Test
    fun `reports unknown version when build info is absent`() {
        val info = InfoController(
            "os-service",
            AppEnvironment("prod"),
            StaticListableBeanFactory().getBeanProvider(BuildProperties::class.java),
        ).info()

        assertEquals(ServiceInfoResponse("os-service", "prod", "unknown"), info)
    }
}
