package com.contra.api.account;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	/**
	 * Reads the account and locks its row until the transaction ends (PostgreSQL: SELECT ... FOR UPDATE).
	 * Any other transaction that wants the same lock waits here. Must run inside a transaction.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id = :id")
	Optional<Account> findByIdForUpdate(UUID id);
}
