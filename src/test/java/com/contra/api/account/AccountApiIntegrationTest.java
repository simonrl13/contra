package com.contra.api.account;

import java.math.BigDecimal;
import java.util.UUID;

import com.contra.api.TestcontainersConfiguration;
import com.contra.api.ledger.LedgerEntryRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whole stack, HTTP to PostgreSQL: open an account, read it back, check the ledger invariant.
 * Deliberately not @Transactional, so the service's own transactions commit as they would in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	LedgerEntryRepository ledgerEntryRepository;

	@Test
	void openedAccountCanBeReadBackAndMatchesItsLedger() throws Exception {
		MvcResult created = mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": "Ana", "currency": "EUR", "initialDeposit": 250.50}
				"""))
				.andExpect(status().isCreated())
				.andReturn();
		String location = created.getResponse().getHeader("Location");

		mockMvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerName").value("Ana"))
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andExpect(jsonPath("$.balance").value(250.50));

		UUID accountId = UUID.fromString(location.substring("/accounts/".length()));
		assertThat(ledgerEntryRepository.sumAmountsByAccountId(accountId)).isEqualByComparingTo(new BigDecimal("250.50"));
	}
}
