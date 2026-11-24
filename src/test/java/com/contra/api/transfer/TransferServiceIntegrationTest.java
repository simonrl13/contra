package com.contra.api.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.contra.api.TestcontainersConfiguration;
import com.contra.api.account.AccountRepository;
import com.contra.api.account.AccountService;
import com.contra.api.account.OpenAccountRequest;
import com.contra.api.common.ConflictException;
import com.contra.api.common.NotFoundException;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.transfer.TransferRejectedException.Reason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.contra.api.ledger.LedgerEntryType.TRANSFER_CREDIT;
import static com.contra.api.ledger.LedgerEntryType.TRANSFER_DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Behaviour of TransferService.transfer against real PostgreSQL. Checks results (balances, rows in
 * the database), not which repository methods are called, so the implementation is free.
 *
 * Not @Transactional: every call commits or rolls back for real, exactly as in production.
 * The ledger repository is a spy: real behaviour, except where a test makes it fail on purpose.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceIntegrationTest {

	private static final Currency EUR = Currency.getInstance("EUR");
	private static final Currency USD = Currency.getInstance("USD");

	@Autowired
	TransferService transferService;

	@Autowired
	AccountService accountService;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	TransferRepository transferRepository;

	@MockitoSpyBean
	LedgerEntryRepository ledgerEntryRepository;

	@Test
	void movesMoneyAndWritesOneDebitAndOneCreditEntry() {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "5.00");

		TransferResponse transfer = transferService.transfer(newKey(), request(ana, bob, "30", EUR));

		assertThat(transfer.fromAccountId()).isEqualTo(ana);
		assertThat(transfer.toAccountId()).isEqualTo(bob);
		assertThat(transfer.amount()).isEqualTo(new BigDecimal("30.00"));
		assertThat(transfer.currency()).isEqualTo(EUR);
		assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transferRepository.findById(transfer.id())).isPresent();

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("35.00"));

		assertThat(ledgerEntriesOf(transfer.id()))
				.extracting(LedgerEntry::getAccountId, LedgerEntry::getType, LedgerEntry::getAmount)
				.containsExactlyInAnyOrder(
						tuple(ana, TRANSFER_DEBIT, new BigDecimal("-30.00")),
						tuple(bob, TRANSFER_CREDIT, new BigDecimal("30.00")));
		assertLedgerMatchesBalance(ana);
		assertLedgerMatchesBalance(bob);
	}

	@Test
	void canEmptyTheSourceAccountExactly() {
		UUID ana = open(EUR, "30.00");
		UUID bob = open(EUR, "0");

		transferService.transfer(newKey(), request(ana, bob, "30.00", EUR));

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("0.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("30.00"));
		assertLedgerMatchesBalance(ana);
		assertLedgerMatchesBalance(bob);
	}

	@Test
	void rejectsInsufficientFundsAndChangesNothing() {
		UUID ana = open(EUR, "10.00");
		UUID bob = open(EUR, "0");
		long transfersBefore = transferRepository.count();

		assertRejected(request(ana, bob, "10.01", EUR), Reason.INSUFFICIENT_FUNDS);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("0.00"));
		assertThat(transferRepository.count()).isEqualTo(transfersBefore);
		assertLedgerMatchesBalance(ana);
	}

	@Test
	void rejectsATransferToTheSameAccount() {
		UUID ana = open(EUR, "10.00");

		assertRejected(request(ana, ana, "1.00", EUR), Reason.SAME_ACCOUNT);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void rejectsAccountsWithDifferentCurrencies() {
		UUID ana = open(EUR, "10.00");
		UUID bob = open(USD, "0");

		assertRejected(request(ana, bob, "1.00", EUR), Reason.CURRENCY_MISMATCH);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void rejectsARequestCurrencyThatDiffersFromTheAccounts() {
		UUID ana = open(EUR, "10.00");
		UUID bob = open(EUR, "0");

		assertRejected(request(ana, bob, "1.00", USD), Reason.CURRENCY_MISMATCH);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
	}

	/** The DTO already blocks these with a 400; the service must not rely on every caller using the DTO. */
	@ParameterizedTest
	@ValueSource(strings = {"0", "0.00", "-5.00"})
	void rejectsANonPositiveAmount(String amount) {
		UUID ana = open(EUR, "10.00");
		UUID bob = open(EUR, "0");

		assertRejected(request(ana, bob, amount, EUR), Reason.AMOUNT_NOT_POSITIVE);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void unknownSourceAccountIsNotFound() {
		UUID unknown = UUID.randomUUID();
		UUID bob = open(EUR, "0");

		assertThatThrownBy(() -> transferService.transfer(newKey(), request(unknown, bob, "1.00", EUR)))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Account " + unknown + " not found");
	}

	@Test
	void unknownTargetAccountIsNotFound() {
		UUID ana = open(EUR, "10.00");
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> transferService.transfer(newKey(), request(ana, unknown, "1.00", EUR)))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Account " + unknown + " not found");

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("10.00"));
	}

	/**
	 * Atomicity: the ledger write fails after the balances were changed in memory (and maybe flushed).
	 * Everything must roll back. Every save method of the ledger repository is made to fail, so this
	 * holds whichever one you use.
	 */
	@Test
	void rollsBackEverythingWhenTheLedgerWriteFails() {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "5.00");
		long transfersBefore = transferRepository.count();

		RuntimeException ledgerFailure = new RuntimeException("simulated ledger failure");
		doThrow(ledgerFailure).when(ledgerEntryRepository).save(any());
		doThrow(ledgerFailure).when(ledgerEntryRepository).saveAndFlush(any());
		doThrow(ledgerFailure).when(ledgerEntryRepository).saveAll(any());
		doThrow(ledgerFailure).when(ledgerEntryRepository).saveAllAndFlush(any());

		assertThatThrownBy(() -> transferService.transfer(newKey(), request(ana, bob, "30.00", EUR)))
				.isSameAs(ledgerFailure);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("100.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("5.00"));
		assertThat(transferRepository.count()).isEqualTo(transfersBefore);
	}

	// --- idempotency ---

	@Test
	void sameKeyAndSameRequestReturnsTheOriginalTransferAndMovesMoneyOnce() {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "0");
		String key = newKey();

		TransferResponse first = transferService.transfer(key, request(ana, bob, "30", EUR));
		TransferResponse retry = transferService.transfer(key, request(ana, bob, "30.00", EUR));

		assertThat(retry).isEqualTo(first);
		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(ledgerEntriesOf(first.id())).hasSize(2);
	}

	@Test
	void sameKeyWithADifferentRequestIsRejectedAndChangesNothing() {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "0");
		String key = newKey();
		transferService.transfer(key, request(ana, bob, "30.00", EUR));

		assertRejected(key, request(bob, ana, "30.00", EUR), Reason.IDEMPOTENCY_KEY_REUSED);

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("30.00"));
	}

	/** Only completed transfers are stored, so a rejected request doesn't use up its key. */
	@Test
	void aRejectedRequestDoesNotUseUpItsKey() {
		UUID ana = open(EUR, "10.00");
		UUID bob = open(EUR, "0");
		String key = newKey();

		assertRejected(key, request(ana, bob, "50.00", EUR), Reason.INSUFFICIENT_FUNDS);
		transferService.transfer(key, request(ana, bob, "5.00", EUR));

		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("5.00"));
	}

	/**
	 * The race: several requests with the same key start at the same instant, so all of them can pass
	 * the "seen this key?" lookup before any has committed. The unique constraint lets exactly one
	 * insert win. Each loser either sees the winner's transfer (if it looked after the commit) or gets
	 * a ConflictException (409). Money moves once.
	 */
	@Test
	void concurrentRequestsWithTheSameKeyMoveMoneyOnce() throws Exception {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "0");
		String key = newKey();
		TransferRequest request = request(ana, bob, "30.00", EUR);
		int threads = 8;

		List<UUID> returnedIds = new ArrayList<>();
		int conflicts = 0;
		CountDownLatch startGate = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<TransferResponse>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					startGate.await();
					return transferService.transfer(key, request);
				}));
			}
			startGate.countDown();
			for (Future<TransferResponse> result : results) {
				try {
					returnedIds.add(result.get(30, TimeUnit.SECONDS).id());
				} catch (ExecutionException failure) {
					assertThat(failure.getCause()).isInstanceOf(ConflictException.class);
					conflicts++;
				}
			}
		}

		assertThat(returnedIds).isNotEmpty();
		assertThat(returnedIds).containsOnly(returnedIds.getFirst());
		assertThat(returnedIds.size() + conflicts).isEqualTo(threads);
		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("30.00"));
		assertLedgerMatchesBalance(ana);
		assertLedgerMatchesBalance(bob);
	}

	// --- helpers ---

	private UUID open(Currency currency, String initialDeposit) {
		return accountService.open(new OpenAccountRequest("Test owner", currency, new BigDecimal(initialDeposit))).id();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	private static TransferRequest request(UUID from, UUID to, String amount, Currency currency) {
		return new TransferRequest(from, to, new BigDecimal(amount), currency);
	}

	private BigDecimal balanceOf(UUID accountId) {
		return accountRepository.findById(accountId).orElseThrow().getBalance();
	}

	private List<LedgerEntry> ledgerEntriesOf(UUID transferId) {
		return ledgerEntryRepository.findAll().stream()
				.filter(entry -> transferId.equals(entry.getTransferId()))
				.toList();
	}

	private void assertRejected(TransferRequest request, Reason expectedReason) {
		assertRejected(newKey(), request, expectedReason);
	}

	private void assertRejected(String key, TransferRequest request, Reason expectedReason) {
		assertThatThrownBy(() -> transferService.transfer(key, request))
				.isInstanceOf(TransferRejectedException.class)
				.extracting("reason")
				.isEqualTo(expectedReason);
	}

	/** The core invariant: an account's balance always equals the sum of its ledger entries. */
	private void assertLedgerMatchesBalance(UUID accountId) {
		assertThat(ledgerEntryRepository.sumAmountsByAccountId(accountId)).isEqualByComparingTo(balanceOf(accountId));
	}
}
