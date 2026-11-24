package com.contra.api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class Account {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String ownerName;

	// Hibernate stores java.util.Currency as its ISO code ("EUR").
	@Column(nullable = false)
	private Currency currency;

	@Column(nullable = false)
	private BigDecimal balance;

	@Column(nullable = false)
	private Instant createdAt;

	// Optimistic locking: Hibernate adds "WHERE version = ?" to every UPDATE and increments it.
	// A wrapper type (Long, not long) so that null means "new entity" to Spring Data's save().
	@Version
	private Long version;

	/** Required by JPA, which creates instances through reflection. Not for application code. */
	protected Account() {
	}

	public Account(String ownerName, Currency currency, BigDecimal openingBalance) {
		this.id = UUID.randomUUID();
		this.ownerName = ownerName;
		this.currency = currency;
		this.balance = openingBalance;
		// PostgreSQL stores microseconds, so drop nanoseconds now and the value read back is identical.
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public Currency getCurrency() {
		return currency;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}
