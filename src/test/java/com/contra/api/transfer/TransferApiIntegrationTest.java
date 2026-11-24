package com.contra.api.transfer;

import java.util.UUID;

import com.contra.api.TestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole stack over HTTP: open two accounts, transfer, then read the transfer and the statement. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void transferIsReadableAndAppearsFirstOnTheStatement() throws Exception {
		String ana = openAccount("100.00");
		String bob = openAccount("0");

		String location = mockMvc.perform(postTransfer(UUID.randomUUID().toString(), transferBody(ana, bob, "30")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amount").value(30.00))
				.andReturn().getResponse().getHeader("Location");
		String transferId = location.substring("/transfers/".length());

		mockMvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(transferId))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(get("/accounts/{id}/transactions", ana))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].type").value("TRANSFER_DEBIT"))
				.andExpect(jsonPath("$.content[0].amount").value(-30.00))
				.andExpect(jsonPath("$.content[0].transferId").value(transferId))
				.andExpect(jsonPath("$.content[1].type").value("OPENING"));

		mockMvc.perform(get("/accounts/{id}", bob))
				.andExpect(jsonPath("$.balance").value(30.00));
	}

	@Test
	void insufficientFundsReturns422WithReason() throws Exception {
		String ana = openAccount("10.00");
		String bob = openAccount("0");

		mockMvc.perform(postTransfer(UUID.randomUUID().toString(), transferBody(ana, bob, "50")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
	}

	@Test
	void retryWithTheSameKeyReturnsTheOriginalTransferAndMovesMoneyOnce() throws Exception {
		String ana = openAccount("100.00");
		String bob = openAccount("0");
		String key = UUID.randomUUID().toString();

		String first = mockMvc.perform(postTransfer(key, transferBody(ana, bob, "30")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		// "30.00" is the same amount as "30": still the same request
		String retry = mockMvc.perform(postTransfer(key, transferBody(ana, bob, "30.00")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		assertThat(retry).isEqualTo(first);
		mockMvc.perform(get("/accounts/{id}", ana)).andExpect(jsonPath("$.balance").value(70.00));
	}

	@Test
	void sameKeyWithADifferentBodyReturns422() throws Exception {
		String ana = openAccount("100.00");
		String bob = openAccount("0");
		String key = UUID.randomUUID().toString();

		mockMvc.perform(postTransfer(key, transferBody(ana, bob, "30"))).andExpect(status().isCreated());

		mockMvc.perform(postTransfer(key, transferBody(ana, bob, "31")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"));
		mockMvc.perform(get("/accounts/{id}", ana)).andExpect(jsonPath("$.balance").value(70.00));
	}

	private static MockHttpServletRequestBuilder postTransfer(String idempotencyKey, String body) {
		return post("/transfers").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String openAccount(String initialDeposit) throws Exception {
		String location = mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": "Test owner", "currency": "EUR", "initialDeposit": %s}
				""".formatted(initialDeposit)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return location.substring("/accounts/".length());
	}

	private static String transferBody(String from, String to, String amount) {
		return """
				{"fromAccountId": "%s", "toAccountId": "%s", "amount": %s, "currency": "EUR"}
				""".formatted(from, to, amount);
	}
}
