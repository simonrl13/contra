package com.contra.api.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.contra.api.account.Account;
import com.contra.api.account.AccountRepository;
import com.contra.api.common.Money;
import com.contra.api.common.NotFoundException;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.transfer.TransferRejectedException.Reason;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

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
	 * Moves money from one account to another, atomically: every check runs before anything changes,
	 * and one transaction covers both balances, the transfer row and both ledger entries.
	 */
	@Transactional
	public TransferResponse transfer(TransferRequest request) {
		BigDecimal amount = Money.normalize(request.amount());
		if (amount.signum() <= 0) {
			throw new TransferRejectedException(Reason.AMOUNT_NOT_POSITIVE);
		}
		if (request.fromAccountId().equals(request.toAccountId())) {
			throw new TransferRejectedException(Reason.SAME_ACCOUNT);
		}

		Account source = findAccount(request.fromAccountId());
		Account target = findAccount(request.toAccountId());

		if (!source.getCurrency().equals(request.currency()) || !target.getCurrency().equals(request.currency())) {
			throw new TransferRejectedException(Reason.CURRENCY_MISMATCH);
		}
		if (!source.hasFundsFor(amount)) {
			throw new TransferRejectedException(Reason.INSUFFICIENT_FUNDS);
		}

		// All checks passed. Balances change in memory; Hibernate writes them at commit (dirty checking).
		source.debit(amount);
		target.credit(amount);

		// The transfer row goes first: the ledger entries reference it through a foreign key.
		Transfer transfer = transferRepository.save(
				new Transfer(source.getId(), target.getId(), amount, request.currency()));
		ledgerEntryRepository.saveAll(List.of(
				LedgerEntry.transferDebit(source.getId(), amount, transfer.getId()),
				LedgerEntry.transferCredit(target.getId(), amount, transfer.getId())));

		return TransferResponse.from(transfer);
	}

	private Account findAccount(UUID id) {
		return accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
	}

	@Transactional(readOnly = true)
	public TransferResponse get(UUID id) {
		return transferRepository.findById(id)
				.map(TransferResponse::from)
				.orElseThrow(() -> new NotFoundException("Transfer", id));
	}
}
