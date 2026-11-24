package com.contra.api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryType;

/** One line of an account statement: a ledger entry as seen through the API. */
public record TransactionResponse(UUID id, LedgerEntryType type, BigDecimal amount, UUID transferId, Instant createdAt) {

	static TransactionResponse from(LedgerEntry entry) {
		return new TransactionResponse(entry.getId(), entry.getType(), entry.getAmount(), entry.getTransferId(),
				entry.getCreatedAt());
	}
}
