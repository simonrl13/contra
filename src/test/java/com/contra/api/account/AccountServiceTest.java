package com.contra.api.account;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.contra.api.common.NotFoundException;
import com.contra.api.ledger.LedgerEntry;
import com.contra.api.ledger.LedgerEntryRepository;
import com.contra.api.ledger.LedgerEntryType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test: no Spring context, no database. Repositories are Mockito mocks. */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

	private static final Currency EUR = Currency.getInstance("EUR");

	@Mock
	AccountRepository accountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	@InjectMocks
	AccountService accountService;

	@BeforeEach
	void saveReturnsTheSameAccount() {
		// lenient: the get(...) tests don't call save
		lenient().when(accountRepository.save(any())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void openWithDepositRecordsAnOpeningLedgerEntry() {
		AccountResponse response = accountService.open(new OpenAccountRequest("Ana", EUR, new BigDecimal("100")));

		ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).save(entry.capture());
		assertThat(entry.getValue().getType()).isEqualTo(LedgerEntryType.OPENING);
		assertThat(entry.getValue().getAmount()).isEqualTo(new BigDecimal("100.00"));
		assertThat(entry.getValue().getAccountId()).isEqualTo(response.id());
		assertThat(entry.getValue().getTransferId()).isNull();
	}

	@Test
	void openNormalizesTheBalanceToTwoDecimals() {
		AccountResponse response = accountService.open(new OpenAccountRequest("Ana", EUR, new BigDecimal("100")));

		// isEqualTo on BigDecimal also compares scale: 100 is not equal to 100.00
		assertThat(response.balance()).isEqualTo(new BigDecimal("100.00"));
	}

	@Test
	void openWithZeroDepositCreatesNoLedgerEntry() {
		AccountResponse response = accountService.open(new OpenAccountRequest("Ana", EUR, BigDecimal.ZERO));

		assertThat(response.balance()).isEqualTo(new BigDecimal("0.00"));
		verify(ledgerEntryRepository, never()).save(any());
	}

	@Test
	void getReturnsTheAccount() {
		Account account = new Account("Ana", EUR, new BigDecimal("5.00"));
		when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

		AccountResponse response = accountService.get(account.getId());

		assertThat(response.id()).isEqualTo(account.getId());
		assertThat(response.ownerName()).isEqualTo("Ana");
	}

	@Test
	void getUnknownAccountThrowsNotFound() {
		UUID unknownId = UUID.randomUUID();
		when(accountRepository.findById(unknownId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.get(unknownId))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Account " + unknownId + " not found");
	}
}
