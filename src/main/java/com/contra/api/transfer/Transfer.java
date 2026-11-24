package com.contra.api.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The record of one completed money movement. Immutable once created. */
@Entity
@Table(name = "transfers")
public class Transfer {

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID fromAccountId;

	@Column(nullable = false)
	private UUID toAccountId;

	@Column(nullable = false)
	private BigDecimal amount;

	@Column(nullable = false)
	private Currency currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransferStatus status;

	@Column(nullable = false)
	private Instant createdAt;

	/** The client's Idempotency-Key header. Unique in the database. */
	@Column(nullable = false)
	private String idempotencyKey;

	/** SHA-256 of the request, to tell a genuine retry from a different request reusing the key. */
	@Column(nullable = false)
	private String requestHash;

	protected Transfer() {
	}

	public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, Currency currency,
			String idempotencyKey, String requestHash) {
		this.id = UUID.randomUUID();
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.amount = amount;
		this.currency = currency;
		this.status = TransferStatus.COMPLETED;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	public UUID getId() {
		return id;
	}

	public UUID getFromAccountId() {
		return fromAccountId;
	}

	public UUID getToAccountId() {
		return toAccountId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Currency getCurrency() {
		return currency;
	}

	public TransferStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}
}
