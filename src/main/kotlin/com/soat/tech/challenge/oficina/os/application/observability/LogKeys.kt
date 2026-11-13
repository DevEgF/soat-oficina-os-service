package com.soat.tech.challenge.oficina.os.application.observability

import org.slf4j.MDC
import org.slf4j.spi.LoggingEventBuilder

/**
 * Adds [key] to the log line unless the MDC already carries it. The structured (JSON) log refuses a name
 * written twice in one line and drops the whole line; while a message is consumed the MDC already has
 * sagaId, messageType and messageId, with the same values the code would add.
 */
fun LoggingEventBuilder.addKeyValueUnlessInMdc(key: String, value: Any?): LoggingEventBuilder =
	if (MDC.get(key) == null) addKeyValue(key, value) else this
