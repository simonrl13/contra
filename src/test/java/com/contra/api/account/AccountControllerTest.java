package com.contra.api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.contra.api.common.NotFoundException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice: only MVC (controller, JSON, validation, GlobalExceptionHandler). No database.
 * The service is replaced by a Mockito mock.
 */
@WebMvcTest(AccountController.class)
class AccountControllerTest {

	private static final UUID ACCOUNT_ID = UUID.fromString("7b0e1e4a-1111-4c5e-9d3a-000000000001");

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	AccountService accountService;

	@Test
	void openReturns201WithLocationAndBody() throws Exception {
		when(accountService.open(any())).thenReturn(new AccountResponse(ACCOUNT_ID, "Ana",
				Currency.getInstance("EUR"), new BigDecimal("100.00"), Instant.parse("2026-01-15T10:00:00Z")));

		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": "Ana", "currency": "EUR", "initialDeposit": 100}
				"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/accounts/" + ACCOUNT_ID))
				.andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andExpect(jsonPath("$.createdAt").value("2026-01-15T10:00:00Z"))
				// checked as text: jsonPath would read 100.00 as the double 100.0 and hide the scale
				.andExpect(content().string(containsString("\"balance\":100.00")));
	}

	@Test
	void openWithInvalidFieldsReturns400ListingEachField() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": " ", "currency": "EUR", "initialDeposit": -1}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.ownerName").exists())
				.andExpect(jsonPath("$.errors.initialDeposit").exists());

		verifyNoInteractions(accountService);
	}

	@Test
	void openWithMoreThanTwoDecimalsReturns400() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": "Ana", "currency": "EUR", "initialDeposit": 10.005}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.initialDeposit").exists());
	}

	@Test
	void openWithUnknownCurrencyReturns400() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"ownerName": "Ana", "currency": "XYZ", "initialDeposit": 10}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors.currency").value("invalid value"));

		verifyNoInteractions(accountService);
	}

	@Test
	void openWithBrokenJsonReturns400() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Malformed JSON request"));
	}

	@Test
	void validationMessagesAreInEnglishWhateverTheClientLocale() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
				.header("Accept-Language", "pt-BR")
				.content("""
				{"ownerName": "", "currency": "EUR", "initialDeposit": 10}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.ownerName").value("must not be blank"));
	}

	@Test
	void getUnknownAccountReturns404ProblemDetail() throws Exception {
		when(accountService.get(ACCOUNT_ID)).thenThrow(new NotFoundException("Account", ACCOUNT_ID));

		mockMvc.perform(get("/accounts/{id}", ACCOUNT_ID))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("Account " + ACCOUNT_ID + " not found"));
	}

	@Test
	void getWithMalformedIdReturns400() throws Exception {
		mockMvc.perform(get("/accounts/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}
}
