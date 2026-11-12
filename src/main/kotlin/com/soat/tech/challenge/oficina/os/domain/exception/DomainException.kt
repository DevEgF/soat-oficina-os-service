package com.soat.tech.challenge.oficina.os.domain.exception

open class DomainException(message: String) : RuntimeException(message)

class InvalidTaxDocumentException(message: String) : DomainException(message)

class InvalidLicensePlateException(message: String) : DomainException(message)

class InvalidStatusTransitionException(
	val currentStatus: String,
	val action: String,
) : DomainException("Invalid transition: status=$currentStatus, action=$action")

class InvalidSagaStateException(
	val sagaStatus: String,
	val action: String,
) : DomainException("Invalid saga state: status=$sagaStatus, action=$action")
