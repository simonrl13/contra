package com.contra.api.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

	/** The ledger's view of an account's balance. Must always equal accounts.balance. */
	@Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
	BigDecimal sumAmountsByAccountId(UUID accountId);

	/**
	 * One page of an account's entries, newest first. The order is fixed in the method name, so a
	 * client can't sort by arbitrary columns. id breaks ties between equal timestamps, which keeps
	 * the order stable between pages. Served by the (account_id, created_at DESC) index.
	 */
	Page<LedgerEntry> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);
}
