package com.soat.tech.challenge.oficina.os.domain.model

import java.util.UUID

data class Customer(
	val id: UUID,
	val taxDocument: TaxDocument,
	val name: String,
	val email: String? = null,
	val phone: String? = null,
)
