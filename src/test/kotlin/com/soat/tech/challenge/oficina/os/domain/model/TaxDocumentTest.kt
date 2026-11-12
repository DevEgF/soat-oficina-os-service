package com.soat.tech.challenge.oficina.os.domain.model

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidTaxDocumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaxDocumentTest {

	@Test
	fun `given formatted CPF when parse then digits normalized`() {
		val d = TaxDocument.parse("529.982.247-25")
		assertEquals("52998224725", d.digits)
	}

	@Test
	fun `given CNPJ when parse then accepts valid`() {
		val d = TaxDocument.parse("11.222.333/0001-81")
		assertEquals("11222333000181", d.digits)
	}

	@Test
	fun `given invalid CPF when parse then throws`() {
		assertFailsWith<InvalidTaxDocumentException> {
			TaxDocument.parse("11111111111")
		}
	}

	@Test
	fun `given wrong length when parse then throws`() {
		assertFailsWith<InvalidTaxDocumentException> {
			TaxDocument.parse("123")
		}
	}

	@Test
	fun `given CPF with wrong check digit when parse then throws`() {
		assertFailsWith<InvalidTaxDocumentException> {
			TaxDocument.parse("52998224724")
		}
	}

	@Test
	fun `given invalid CNPJ when parse then throws`() {
		assertFailsWith<InvalidTaxDocumentException> {
			TaxDocument.parse("11.222.333/0001-80")
		}
		assertFailsWith<InvalidTaxDocumentException> {
			TaxDocument.parse("11111111111111")
		}
	}
}
