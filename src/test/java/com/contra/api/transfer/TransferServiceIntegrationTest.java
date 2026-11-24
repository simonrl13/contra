package com.contra.api.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

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
	 * The race: several identical requests with the same key start at the same instant. They all lock the
	 * same two accounts, so they run one after another, and each one after the first finds the committed
	 * transfer and replays it. Money moves once, and nobody gets an error.
	 */
	@Test
	void concurrentIdenticalRequestsWithTheSameKeyMoveMoneyOnceAndAllGetTheSameTransfer() throws Exception {
		UUID ana = open(EUR, "100.00");
		UUID bob = open(EUR, "0");
		String key = newKey();
		TransferRequest request = request(ana, bob, "30.00", EUR);

		List<Outcome> outcomes = runConcurrently(8, i -> transferService.transfer(key, request));

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
		assertThat(outcomes).extracting(outcome -> outcome.transfer().id())
				.containsOnly(outcomes.getFirst().transfer().id());
		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(balanceOf(bob)).isEqualTo(new BigDecimal("30.00"));
		assertLedgerMatchesBalance(ana);
		assertLedgerMatchesBalance(bob);
	}

	/**
	 * Same key, different requests (each to another target account), started together. They don't
	 * share all their locks, so several can pass the key lookup; the unique constraint lets exactly one
	 * insert win. Each loser gets a 409 (it raced) or IDEMPOTENCY_KEY_REUSED (it looked after the commit).
	 */
	@Test
	void concurrentDifferentRequestsWithTheSameKeyMoveMoneyOnce() throws Exception {
		UUID ana = open(EUR, "100.00");
		List<UUID> targets = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			targets.add(open(EUR, "0"));
		}
		String key = newKey();

		List<Outcome> outcomes = runConcurrently(targets.size(),
				i -> transferService.transfer(key, request(ana, targets.get(i), "30.00", EUR)));

		assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(1);
		assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
				.allSatisfy(outcome -> assertThat(isConflictOrKeyReused(outcome.failure())).isTrue());
		assertThat(balanceOf(ana)).isEqualTo(new BigDecimal("70.00"));
		assertThat(targets.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualTo(new BigDecimal("30.00"));
	}

	private static boolean isConflictOrKeyReused(Throwable failure) {
		return failure instanceof ConflictException
				|| failure instanceof TransferRejectedException rejected && rejected.getReason() == Reason.IDEMPOTENCY_KEY_REUSED;
	}

	// --- concurrency ---

	/**
	 * Many transfers between a few accounts, in both directions, all released at once by a start gate.
	 * Without row locks, concurrent read-modify-write on a balance either loses an update or (with
	 * @Version) fails with an optimistic-lock error. Without lock ordering, A→B and B→A deadlock.
	 * Either way an unexpected exception shows up here. Allowed outcomes: success or INSUFFICIENT_FUNDS.
	 */
	@Test
	void concurrentTransfersKeepEveryInvariant() throws Exception {
		List<UUID> accounts = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			accounts.add(open(EUR, "100.00"));
		}
		BigDecimal totalBefore = new BigDecimal("400.00");
		int transfers = 200;
		Random random = new Random(42); // fixed seed: the same transfers on every run
		List<TransferRequest> requests = new ArrayList<>();
		for (int i = 0; i < transfers; i++) {
			int from = random.nextInt(accounts.size());
			int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
			BigDecimal amount = BigDecimal.valueOf(100 + random.nextInt(6000), 2); // 1.00 to 60.99
			requests.add(new TransferRequest(accounts.get(from), accounts.get(to), amount, EUR));
		}

		List<Outcome> outcomes = runConcurrently(transfers, 16, i -> transferService.transfer(newKey(), requests.get(i)));

		List<Throwable> unexpected = outcomes.stream()
				.map(Outcome::failure)
				.filter(failure -> failure != null && !isInsufficientFunds(failure))
				.toList();
		assertThat(unexpected).isEmpty();
		assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).isNotEmpty();
		assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null).isNotEmpty(); // funds really ran out

		BigDecimal totalAfter = BigDecimal.ZERO;
		for (UUID account : accounts) {
			assertThat(balanceOf(account)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
			assertLedgerMatchesBalance(account);
			totalAfter = totalAfter.add(balanceOf(account));
		}
		assertThat(totalAfter).isEqualTo(totalBefore);
	}

	private static boolean isInsufficientFunds(Throwable failure) {
		return failure instanceof TransferRejectedException rejected && rejected.getReason() == Reason.INSUFFICIENT_FUNDS;
	}

	/** The result of one concurrent call: a transfer, or the exception it threw. */
	private record Outcome(TransferResponse transfer, Throwable failure) {
	}

	private List<Outcome> runConcurrently(int calls, IntFunction<TransferResponse> call) throws Exception {
		return runConcurrently(calls, calls, call);
	}

	/**
	 * Runs the calls on a thread pool, all waiting on one start gate, so they hit the database at the
	 * same moment instead of one by one as the threads happen to start.
	 */
	private List<Outcome> runConcurrently(int calls, int threads, IntFunction<TransferResponse> call) throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<TransferResponse>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < calls; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					startGate.await();
					return call.apply(index);
				}));
			}
			startGate.countDown();

			List<Outcome> outcomes = new ArrayList<>();
			for (Future<TransferResponse> future : futures) {
				try {
					outcomes.add(new Outcome(future.get(60, TimeUnit.SECONDS), null));
				} catch (ExecutionException failure) {
					outcomes.add(new Outcome(null, failure.getCause()));
				}
			}
			return outcomes;
		}
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
