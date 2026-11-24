package com.contra.api.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
		Currency currency, TransferStatus status, Instant createdAt) {

	public static TransferResponse from(Transfer transfer) {
		return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
				transfer.getAmount(), transfer.getCurrency(), transfer.getStatus(), transfer.getCreatedAt());
	}
}
