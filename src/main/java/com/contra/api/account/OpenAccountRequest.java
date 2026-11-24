package com.contra.api.account;

import java.math.BigDecimal;
import java.util.Currency;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /accounts. Jackson turns "EUR" into java.util.Currency and rejects unknown codes (400).
 */
public record OpenAccountRequest(
		@NotBlank @Size(max = 100) String ownerName,
		@NotNull Currency currency,
		@NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal initialDeposit) {
}
