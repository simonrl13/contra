package com.contra.api.ledger;

public enum LedgerEntryType {
	/** Initial deposit when an account is opened. Positive, no transfer. */
	OPENING,
	/** Money leaving the source account of a transfer. Negative. */
	TRANSFER_DEBIT,
	/** Money arriving at the target account of a transfer. Positive. */
	TRANSFER_CREDIT
}
