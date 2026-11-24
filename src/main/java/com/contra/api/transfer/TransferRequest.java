package com.contra.api.transfer;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /transfers. The client states the currency it expects to move: if it doesn't match
 * both accounts, the transfer is rejected rather than moving money in a currency the client didn't mean.
 *
 * @Positive here gives a clean 400 at the API boundary. The service still enforces its own rules,
 * because it can't assume every caller went through this DTO.
 */
public record TransferRequest(
		@NotNull UUID fromAccountId,
		@NotNull UUID toAccountId,
		@NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotNull Currency currency) {
}
