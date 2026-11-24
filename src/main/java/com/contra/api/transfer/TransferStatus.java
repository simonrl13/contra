package com.contra.api.transfer;

/**
 * v1 transfers are synchronous: they either commit as COMPLETED or roll back and leave no row.
 * More states (PENDING, FAILED, REVERSED) only become useful with asynchronous processing.
 */
public enum TransferStatus {
	COMPLETED
}
