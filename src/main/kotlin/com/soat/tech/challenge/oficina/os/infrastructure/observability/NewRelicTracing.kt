package com.soat.tech.challenge.oficina.os.infrastructure.observability

import com.newrelic.api.agent.ConcurrentHashMapHeaders
import com.newrelic.api.agent.HeaderType
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.TransportType
import com.soat.tech.challenge.oficina.os.application.observability.Tracing
import org.springframework.stereotype.Component

/**
 * The only class that talks to the New Relic API. The agent (-javaagent, see the Dockerfile) replaces the
 * no-op implementation of the API at runtime; without it every call here does nothing.
 */
@Component
class NewRelicTracing : Tracing {

	override fun acceptMessageTraceHeaders(headers: Map<String, Any?>) {
		NewRelic.getAgent().transaction.acceptDistributedTraceHeaders(TransportType.AMQP, traceHeaders(headers))
	}

	override fun nameTransaction(category: String, name: String) {
		NewRelic.setTransactionName(category, name)
	}

	override fun addAttributes(attributes: Map<String, Any>) {
		attributes.forEach { (key, value) ->
			when (value) {
				is Number -> NewRelic.addCustomParameter(key, value)
				is Boolean -> NewRelic.addCustomParameter(key, value)
				else -> NewRelic.addCustomParameter(key, value.toString())
			}
		}
	}

	override fun recordEvent(eventType: String, attributes: Map<String, Any>) {
		NewRelic.getAgent().insights.recordCustomEvent(eventType, attributes)
	}

	companion object {
		/** AMQP headers as the agent reads them: only text values (the trace headers are strings). */
		fun traceHeaders(headers: Map<String, Any?>): ConcurrentHashMapHeaders {
			val result = ConcurrentHashMapHeaders.build(HeaderType.MESSAGE)
			headers.forEach { (name, value) ->
				if (value is CharSequence) result.addHeader(name, value.toString())
			}
			return result
		}
	}
}
