package com.contra.api.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money rules shared by accounts and transfers. */
public final class Money {

	public static final int SCALE = 2;

	private Money() {
	}

	/**
	 * Returns the amount with exactly 2 decimals (10 becomes 10.00).
	 * RoundingMode.UNNECESSARY: we never round money silently. Request validation (@Digits) already
	 * rejects more than 2 decimals, so this throws ArithmeticException only on a programming error.
	 */
	public static BigDecimal normalize(BigDecimal amount) {
		return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
	}
}
