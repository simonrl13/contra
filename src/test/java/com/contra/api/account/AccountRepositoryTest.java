package com.contra.api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.contra.api.TestcontainersConfiguration;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.ledger.LedgerEntryType;
import com.contra.api.transfer.Transfer;
import com.contra.api.transfer.TransferRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
	TransferRepository transferRepository;

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
		Transfer transfer = transferBetween(account, accountRepository.save(new Account("Bob", EUR, BigDecimal.ZERO)));
		LedgerEntry wrongSign = new LedgerEntry(account.getId(), LedgerEntryType.TRANSFER_DEBIT,
				new BigDecimal("5.00"), transfer.getId());

		assertThatThrownBy(() -> ledgerEntryRepository.saveAndFlush(wrongSign))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ledger_entries_sign_matches_type");
	}

	@Test
	void databaseRejectsALedgerEntryForAnUnknownTransfer() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("10.00")));
		LedgerEntry orphan = new LedgerEntry(account.getId(), LedgerEntryType.TRANSFER_DEBIT,
				new BigDecimal("-5.00"), UUID.randomUUID());

		assertThatThrownBy(() -> ledgerEntryRepository.saveAndFlush(orphan))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ledger_entries_transfer_id_fk");
	}

	@Test
	void ledgerSumIsTheSumOfSignedEntries() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("70.00")));
		Transfer transfer = transferBetween(account, accountRepository.save(new Account("Bob", EUR, BigDecimal.ZERO)));
		ledgerEntryRepository.save(LedgerEntry.opening(account.getId(), new BigDecimal("100.00")));
		ledgerEntryRepository.save(new LedgerEntry(account.getId(), LedgerEntryType.TRANSFER_DEBIT,
				new BigDecimal("-30.00"), transfer.getId()));

		assertThat(ledgerEntryRepository.sumAmountsByAccountId(account.getId()))
				.isEqualByComparingTo("70.00");
	}

	@Test
	void entriesArePagedNewestFirst() {
		Account account = accountRepository.saveAndFlush(new Account("Ana", EUR, new BigDecimal("6.00")));
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		// Saved out of order, with explicit timestamps, so the test can't pass by insertion order.
		LedgerEntry middle = openingAt(account, "2.00", start.plusSeconds(60));
		LedgerEntry oldest = openingAt(account, "1.00", start);
		LedgerEntry newest = openingAt(account, "3.00", start.plusSeconds(120));

		Page<LedgerEntry> firstPage = ledgerEntryRepository
				.findByAccountIdOrderByCreatedAtDescIdDesc(account.getId(), PageRequest.of(0, 2));
		Page<LedgerEntry> secondPage = ledgerEntryRepository
				.findByAccountIdOrderByCreatedAtDescIdDesc(account.getId(), PageRequest.of(1, 2));

		assertThat(firstPage.getContent()).extracting(LedgerEntry::getId).containsExactly(newest.getId(), middle.getId());
		assertThat(secondPage.getContent()).extracting(LedgerEntry::getId).containsExactly(oldest.getId());
		assertThat(firstPage.getTotalElements()).isEqualTo(3);
	}

	private Transfer transferBetween(Account from, Account to) {
		return transferRepository.save(new Transfer(from.getId(), to.getId(), new BigDecimal("1.00"), EUR));
	}

	private LedgerEntry openingAt(Account account, String amount, Instant createdAt) {
		LedgerEntry entry = LedgerEntry.opening(account.getId(), new BigDecimal(amount));
		ReflectionTestUtils.setField(entry, "createdAt", createdAt); // test-only: no setter in production code
		return ledgerEntryRepository.saveAndFlush(entry);
	}

	@Test
	void ledgerSumOfAnAccountWithoutEntriesIsZero() {
		assertThat(ledgerEntryRepository.sumAmountsByAccountId(UUID.randomUUID()))
				.isEqualByComparingTo("0");
	}
}
