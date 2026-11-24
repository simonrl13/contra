package com.contra.api.transfer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.contra.api.account.Account;
import com.contra.api.account.AccountRepository;
import com.contra.api.common.ConflictException;
import com.contra.api.common.Money;
import com.contra.api.common.NotFoundException;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.transfer.TransferRejectedException.Reason;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

	private static final String IDEMPOTENCY_KEY_CONSTRAINT = "transfers_idempotency_key_unique";

	private final AccountRepository accountRepository;
	private final TransferRepository transferRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
			LedgerEntryRepository ledgerEntryRepository) {
		this.accountRepository = accountRepository;
		this.transferRepository = transferRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
	}

	/**
	 * Moves money from one account to another, atomically and at most once per Idempotency-Key.
	 * Every check runs before anything changes, and one transaction covers both balances, the
	 * transfer row and both ledger entries.
	 *
	 * Concurrency: both account rows are locked (SELECT ... FOR UPDATE) before the balance is read,
	 * smaller id first, and stay locked until commit. See lockBothAccounts.
	 */
	@Transactional
	public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
		BigDecimal amount = Money.normalize(request.amount());
		String requestHash = requestHash(request, amount);

		if (amount.signum() <= 0) {
			throw new TransferRejectedException(Reason.AMOUNT_NOT_POSITIVE);
		}
		if (request.fromAccountId().equals(request.toAccountId())) {
			throw new TransferRejectedException(Reason.SAME_ACCOUNT);
		}

		LockedAccounts accounts = lockBothAccounts(request.fromAccountId(), request.toAccountId());
		Account source = accounts.source();
		Account target = accounts.target();

		// Looked up only after the locks: a duplicate of a transfer still in flight on the same accounts
		// waits for it to commit, then finds it here and replays it instead of failing with a 409.
		Optional<Transfer> earlier = transferRepository.findByIdempotencyKey(idempotencyKey);
		if (earlier.isPresent()) {
			return replay(earlier.get(), requestHash);
		}

		if (!source.getCurrency().equals(request.currency()) || !target.getCurrency().equals(request.currency())) {
			throw new TransferRejectedException(Reason.CURRENCY_MISMATCH);
		}
		if (!source.hasFundsFor(amount)) {
			throw new TransferRejectedException(Reason.INSUFFICIENT_FUNDS);
		}

		// All checks passed. Balances change in memory; Hibernate writes them at flush (dirty checking).
		source.debit(amount);
		target.credit(amount);

		// The transfer row goes first: the ledger entries reference it through a foreign key.
		Transfer transfer = insertOnce(new Transfer(source.getId(), target.getId(), amount, request.currency(),
				idempotencyKey, requestHash));
		ledgerEntryRepository.saveAll(List.of(
				LedgerEntry.transferDebit(source.getId(), amount, transfer.getId()),
				LedgerEntry.transferCredit(target.getId(), amount, transfer.getId())));

		return TransferResponse.from(transfer);
	}

	/** Same key seen before: the same request gets the original result, a different one is refused. */
	private static TransferResponse replay(Transfer earlier, String requestHash) {
		if (!earlier.getRequestHash().equals(requestHash)) {
			throw new TransferRejectedException(Reason.IDEMPOTENCY_KEY_REUSED);
		}
		return TransferResponse.from(earlier);
	}

	/**
	 * Inserts the transfer now (flush) instead of at commit, so a duplicate Idempotency-Key surfaces here.
	 * That happens when two requests with the same key both passed the lookup above: PostgreSQL makes
	 * the second insert wait for the first transaction, then fails it on the unique constraint. This
	 * transaction then rolls back, its balance changes included, and the client gets a 409. A retry
	 * finds the committed transfer through the lookup.
	 */
	private Transfer insertOnce(Transfer transfer) {
		try {
			return transferRepository.saveAndFlush(transfer);
		} catch (DataIntegrityViolationException exception) {
			if (isDuplicateIdempotencyKey(exception)) {
				throw new ConflictException("A request with this Idempotency-Key was processed concurrently. Retry to get its result.");
			}
			throw exception;
		}
	}

	private static boolean isDuplicateIdempotencyKey(DataIntegrityViolationException exception) {
		return exception.getCause() instanceof ConstraintViolationException violation
				&& IDEMPOTENCY_KEY_CONSTRAINT.equals(violation.getConstraintName());
	}

	/** SHA-256 over the fields that define the transfer. The amount is normalised, so 30 and 30.00 match. */
	private static String requestHash(TransferRequest request, BigDecimal normalizedAmount) {
		String canonical = request.fromAccountId() + "|" + request.toAccountId() + "|"
				+ normalizedAmount.toPlainString() + "|" + request.currency().getCurrencyCode();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("Every JVM must support SHA-256", impossible);
		}
	}

	private record LockedAccounts(Account source, Account target) {
	}

	/**
	 * Locks both rows in one global order, smaller UUID first, whichever is source or target.
	 * Without a fixed order, A→B locking A then B while B→A locks B then A can deadlock: each holds
	 * one row and waits for the other. With a fixed order, both transfers ask for the same row first,
	 * so one simply waits for the other. The order only has to be the same everywhere in this
	 * application; Java's UUID.compareTo is used consistently.
	 */
	private LockedAccounts lockBothAccounts(UUID sourceId, UUID targetId) {
		if (sourceId.compareTo(targetId) < 0) {
			Account source = lockAccount(sourceId);
			return new LockedAccounts(source, lockAccount(targetId));
		}
		Account target = lockAccount(targetId);
		return new LockedAccounts(lockAccount(sourceId), target);
	}

	private Account lockAccount(UUID id) {
		return accountRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Account", id));
	}

	@Transactional(readOnly = true)
	public TransferResponse get(UUID id) {
		return transferRepository.findById(id)
				.map(TransferResponse::from)
				.orElseThrow(() -> new NotFoundException("Transfer", id));
	}
}
