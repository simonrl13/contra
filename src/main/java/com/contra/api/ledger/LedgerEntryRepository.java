package com.contra.api.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

	/** The ledger's view of an account's balance. Must always equal accounts.balance. */
	@Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
	BigDecimal sumAmountsByAccountId(UUID accountId);
}
