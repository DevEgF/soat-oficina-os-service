package com.soat.tech.challenge.oficina.os.application.observability

/**
 * Port to the APM agent (New Relic in the cluster). Every call acts on the transaction of the current
 * thread; without the agent the adapter does nothing, so tests and local runs behave the same.
 */
interface Tracing {

	/**
	 * Continues the distributed trace carried by the headers of a consumed message (traceparent,
	 * tracestate, newrelic). Must be called before anything else in the transaction of the message.
	 */
	fun acceptMessageTraceHeaders(headers: Map<String, Any?>)

	/** Names the current transaction as [category]/[name] (for example Message/os.events/QuoteApproved). */
	fun nameTransaction(category: String, name: String)

	/** Custom attributes of the current transaction. No personal data. */
	fun addAttributes(attributes: Map<String, Any>)

	/** Custom event (queried by dashboards). Values are strings, numbers or booleans; no personal data. */
	fun recordEvent(eventType: String, attributes: Map<String, Any>)
}
