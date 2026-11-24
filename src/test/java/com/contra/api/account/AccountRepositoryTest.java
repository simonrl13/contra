package com.contra.api.account;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.contra.api.TestcontainersConfiguration;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.ledger.LedgerEntryType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA slice against real PostgreSQL 16 with the Flyway schema: entity mapping, the repository query
 * and the database constraints. Each test runs in a transaction that is rolled back afterwards.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class AccountRepositoryTest {

	private static final Currency EUR = Currency.getInstance("EUR");

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	TestEntityManager entityManager;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void accountSurvivesARoundTripThroughTheDatabase() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("100.00")));
		entityManager.clear(); // forget the cached object, so findById really reads the row

		Account loaded = accountRepository.findById(account.getId()).orElseThrow();

		assertThat(loaded.getOwnerName()).isEqualTo("Ana");
		assertThat(loaded.getCurrency()).isEqualTo(EUR);
		assertThat(loaded.getBalance()).isEqualTo(new BigDecimal("100.00"));
		assertThat(loaded.getCreatedAt()).isEqualTo(account.getCreatedAt());
		assertThat(loaded.getVersion()).isZero();
	}

	@Test
	void databaseRejectsANegativeBalance() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("10.00")));

		// Bypasses the Java code on purpose: this tests the CHECK constraint itself.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE accounts SET balance = -0.01 WHERE id = ?", account.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_balance_not_negative");
	}

	@Test
	void databaseRejectsADebitWithAPositiveAmount() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("10.00")));
		LedgerEntry wrongSign = new LedgerEntry(account.getId(), LedgerEntryType.TRANSFER_DEBIT,
				new BigDecimal("5.00"), UUID.randomUUID());

		assertThatThrownBy(() -> ledgerEntryRepository.saveAndFlush(wrongSign))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ledger_entries_sign_matches_type");
	}

	@Test
	void ledgerSumIsTheSumOfSignedEntries() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("70.00")));
		ledgerEntryRepository.save(LedgerEntry.opening(account.getId(), new BigDecimal("100.00")));
		ledgerEntryRepository.save(new LedgerEntry(account.getId(), LedgerEntryType.TRANSFER_DEBIT,
				new BigDecimal("-30.00"), UUID.randomUUID()));

		assertThat(ledgerEntryRepository.sumAmountsByAccountId(account.getId()))
				.isEqualByComparingTo("70.00");
	}

	@Test
	void ledgerSumOfAnAccountWithoutEntriesIsZero() {
		assertThat(ledgerEntryRepository.sumAmountsByAccountId(UUID.randomUUID()))
				.isEqualByComparingTo("0");
	}
}
