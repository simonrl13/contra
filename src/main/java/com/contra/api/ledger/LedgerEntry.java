package com.contra.api.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One movement of money on one account. Append-only: entries are never updated or deleted.
 * Amount is signed (credit > 0, debit < 0), so an account's balance = sum of its entries.
 *
 * Accounts and transfers are referenced by id, not @ManyToOne: we never navigate from an entry to its
 * account, and plain ids mean no lazy-loading surprises.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING) // store the name, not the ordinal, so reordering the enum is safe
	@Column(nullable = false)
	private LedgerEntryType type;

	@Column(nullable = false)
	private BigDecimal amount;

	private UUID transferId;

	@Column(nullable = false)
	private Instant createdAt;

	protected LedgerEntry() {
	}

	public LedgerEntry(UUID accountId, LedgerEntryType type, BigDecimal amount, UUID transferId) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.type = type;
		this.amount = amount;
		this.transferId = transferId;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public static LedgerEntry opening(UUID accountId, BigDecimal amount) {
		return new LedgerEntry(accountId, LedgerEntryType.OPENING, amount, null);
	}

	/** Money leaving an account. Takes the positive transfer amount and stores it negated. */
	public static LedgerEntry transferDebit(UUID accountId, BigDecimal amount, UUID transferId) {
		return new LedgerEntry(accountId, LedgerEntryType.TRANSFER_DEBIT, amount.negate(), transferId);
	}

	/** Money arriving on an account. Takes the positive transfer amount and stores it as is. */
	public static LedgerEntry transferCredit(UUID accountId, BigDecimal amount, UUID transferId) {
		return new LedgerEntry(accountId, LedgerEntryType.TRANSFER_CREDIT, amount, transferId);
	}

	public UUID getId() {
		return id;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public LedgerEntryType getType() {
		return type;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public UUID getTransferId() {
		return transferId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
