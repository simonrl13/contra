package com.contra.api.account;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.contra.api.ledger.LedgerEntry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit tests, no Spring: the guards on Account and the signs set by the LedgerEntry factories. */
class AccountTest {

	private final Account account = new Account("Ana", Currency.getInstance("EUR"), new BigDecimal("10.00"));

	@Test
	void debitAndCreditChangeTheBalance() {
		account.debit(new BigDecimal("10.00"));
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));

		account.credit(new BigDecimal("2.50"));
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("2.50"));
	}

	@Test
	void debitRefusesToGoNegative() {
		assertThatThrownBy(() -> account.debit(new BigDecimal("10.01"))).isInstanceOf(IllegalStateException.class);
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void debitAndCreditRefuseNonPositiveAmounts() {
		assertThatThrownBy(() -> account.debit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.credit(new BigDecimal("-1.00"))).isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void ledgerFactoriesSetTheSign() {
		UUID transferId = UUID.randomUUID();
		assertThat(LedgerEntry.transferDebit(account.getId(), new BigDecimal("3.00"), transferId).getAmount())
				.isEqualTo(new BigDecimal("-3.00"));
		assertThat(LedgerEntry.transferCredit(account.getId(), new BigDecimal("3.00"), transferId).getAmount())
				.isEqualTo(new BigDecimal("3.00"));
	}
}
