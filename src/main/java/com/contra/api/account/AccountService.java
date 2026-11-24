package com.contra.api.account;

import java.math.BigDecimal;
import java.util.UUID;

import com.contra.api.common.Money;
import com.contra.api.common.NotFoundException;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
		this.accountRepository = accountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
	}

	/**
	 * Opens an account. A non-zero initial deposit is recorded as an OPENING ledger entry in the same
	 * transaction, so "sum of entries = balance" holds from the first moment.
	 */
	@Transactional
	public AccountResponse open(OpenAccountRequest request) {
		BigDecimal initialDeposit = Money.normalize(request.initialDeposit());
		Account account = accountRepository.save(new Account(request.ownerName(), request.currency(), initialDeposit));

		if (initialDeposit.signum() > 0) {
			ledgerEntryRepository.save(LedgerEntry.opening(account.getId(), initialDeposit));
		}
		return AccountResponse.from(account);
	}

	@Transactional(readOnly = true)
	public AccountResponse get(UUID id) {
		return accountRepository.findById(id)
				.map(AccountResponse::from)
				.orElseThrow(() -> new NotFoundException("Account", id));
	}

	/** An account's ledger entries, newest first. 404 for an unknown account rather than an empty page. */
	@Transactional(readOnly = true)
	public Page<TransactionResponse> listTransactions(UUID accountId, int page, int size) {
		if (!accountRepository.existsById(accountId)) {
			throw new NotFoundException("Account", accountId);
		}
		return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size))
				.map(TransactionResponse::from);
	}
}
