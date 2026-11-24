package com.contra.api.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.contra.api.common.NotFoundException;
import com.contra.api.transfer.TransferRejectedException.Reason;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice for /transfers: HTTP mapping only. The service is a mock, so these pass before Stage 3's logic exists. */
@WebMvcTest(TransferController.class)
class TransferControllerTest {

	private static final UUID TRANSFER_ID = UUID.fromString("5d6c1a2b-2222-4c5e-9d3a-000000000001");
	private static final UUID FROM = UUID.fromString("7b0e1e4a-1111-4c5e-9d3a-000000000001");
	private static final UUID TO = UUID.fromString("7b0e1e4a-1111-4c5e-9d3a-000000000002");

	private static final String VALID_BODY = """
			{"fromAccountId": "%s", "toAccountId": "%s", "amount": 30, "currency": "EUR"}
			""".formatted(FROM, TO);

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	TransferService transferService;

	@Test
	void createReturns201WithLocationAndBody() throws Exception {
		when(transferService.transfer(any())).thenReturn(new TransferResponse(TRANSFER_ID, FROM, TO,
				new BigDecimal("30.00"), Currency.getInstance("EUR"), TransferStatus.COMPLETED,
				Instant.parse("2026-01-15T10:00:00Z")));

		mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/transfers/" + TRANSFER_ID))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.fromAccountId").value(FROM.toString()));
	}

	@Test
	void businessRuleViolationReturns422WithReason() throws Exception {
		when(transferService.transfer(any())).thenThrow(new TransferRejectedException(Reason.INSUFFICIENT_FUNDS));

		mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(422))
				.andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"))
				.andExpect(jsonPath("$.detail").value("Insufficient funds in the source account"));
	}

	@Test
	void unknownAccountReturns404() throws Exception {
		when(transferService.transfer(any())).thenThrow(new NotFoundException("Account", FROM));

		mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isNotFound());
	}

	@Test
	void missingFieldsAndNonPositiveAmountReturn400() throws Exception {
		mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content("""
				{"amount": 0}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fromAccountId").exists())
				.andExpect(jsonPath("$.errors.toAccountId").exists())
				.andExpect(jsonPath("$.errors.currency").exists())
				.andExpect(jsonPath("$.errors.amount").value("must be greater than 0"));

		verifyNoInteractions(transferService);
	}

	@Test
	void getUnknownTransferReturns404() throws Exception {
		when(transferService.get(TRANSFER_ID)).thenThrow(new NotFoundException("Transfer", TRANSFER_ID));

		mockMvc.perform(get("/transfers/{id}", TRANSFER_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Transfer " + TRANSFER_ID + " not found"));
	}
}
