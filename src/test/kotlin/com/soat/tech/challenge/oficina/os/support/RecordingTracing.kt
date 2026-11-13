package com.soat.tech.challenge.oficina.os.support

import com.soat.tech.challenge.oficina.os.application.observability.Tracing

/** [Tracing] that keeps every call, for assertions in unit tests. */
class RecordingTracing : Tracing {

	data class Event(val type: String, val attributes: Map<String, Any>)

	val acceptedHeaders = mutableListOf<Map<String, Any?>>()
	val transactionNames = mutableListOf<String>()
	val attributes = linkedMapOf<String, Any>()
	val events = mutableListOf<Event>()

	override fun acceptMessageTraceHeaders(headers: Map<String, Any?>) {
		acceptedHeaders += headers.toMap()
	}

	override fun nameTransaction(category: String, name: String) {
		transactionNames += "$category/$name"
	}

	override fun addAttributes(attributes: Map<String, Any>) {
		this.attributes.putAll(attributes)
	}

	override fun recordEvent(eventType: String, attributes: Map<String, Any>) {
		events += Event(eventType, attributes)
	}

	fun events(type: String): List<Map<String, Any>> = events.filter { it.type == type }.map { it.attributes }
}
