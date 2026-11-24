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

	/** True if the balance covers the amount. Leaving exactly 0 is allowed. */
	public boolean hasFundsFor(BigDecimal amount) {
		return balance.compareTo(amount) >= 0;
	}

	/**
	 * Takes money out. The service checks the business rules first and rejects with a proper reason;
	 * these guards only stop a programming error from corrupting the balance.
	 */
	public void debit(BigDecimal amount) {
		requirePositive(amount);
		if (!hasFundsFor(amount)) {
			throw new IllegalStateException("Debit of " + amount + " would make account " + id + " negative");
		}
		balance = balance.subtract(amount);
	}

	public void credit(BigDecimal amount) {
		requirePositive(amount);
		balance = balance.add(amount);
	}

	private static void requirePositive(BigDecimal amount) {
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("Amount must be positive, was " + amount);
		}
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
