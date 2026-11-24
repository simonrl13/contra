package com.contra.api.transfer;

/**
 * A transfer that breaks a business rule. Mapped to 422 by GlobalExceptionHandler, with the reason
 * as a machine-readable "reason" property so clients don't have to parse the message.
 */
public class TransferRejectedException extends RuntimeException {

	public enum Reason {
		AMOUNT_NOT_POSITIVE("Amount must be greater than zero"),
		SAME_ACCOUNT("Source and target account must be different"),
		CURRENCY_MISMATCH("Both accounts and the transfer must use the same currency"),
		INSUFFICIENT_FUNDS("Insufficient funds in the source account"),
		IDEMPOTENCY_KEY_REUSED("This Idempotency-Key was already used for a different request");

		private final String message;

		Reason(String message) {
			this.message = message;
		}
	}

	private final Reason reason;

	public TransferRejectedException(Reason reason) {
		super(reason.message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
