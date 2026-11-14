package com.soat.tech.challenge.oficina.os.application

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidTaxDocumentException
import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Customer lookup for other services (the customer authentication service), by tax document. */
@Service
class CustomerLookupService(private val customers: CustomerRepository) {

	/**
	 * [taxId] must be the CPF or CNPJ with digits only (no dots, dashes or slashes); an invalid document is
	 * an [InvalidTaxDocumentException]. Returns null when no customer has that document.
	 */
	@Transactional(readOnly = true)
	fun findByTaxId(taxId: String): Customer? {
		if (taxId.isEmpty() || taxId.any { it !in '0'..'9' }) {
			throw InvalidTaxDocumentException("Document must contain digits only")
		}
		return customers.findByTaxDocument(TaxDocument.parse(taxId))
	}
}
