package com.contra.api.account;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

	private final AccountService accountService;

	public AccountController(AccountService accountService) {
		this.accountService = accountService;
	}

	/** 201 Created, with a Location header pointing at the new account. */
	@PostMapping
	public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
		AccountResponse account = accountService.open(request);
		return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(account);
	}

	@GetMapping("/{id}")
	public AccountResponse get(@PathVariable UUID id) {
		return accountService.get(id);
	}

	/**
	 * Paginated statement, newest first. The constraints on page/size are checked by Spring MVC's
	 * built-in method validation (400 if broken). size is capped so a client can't ask for everything at once.
	 * PagedModel is Spring Data's stable JSON shape for a page: {"content": [...], "page": {...}}.
	 */
	@GetMapping("/{id}/transactions")
	public PagedModel<TransactionResponse> transactions(@PathVariable UUID id,
			@RequestParam(defaultValue = "0") @PositiveOrZero int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new PagedModel<>(accountService.listTransactions(id, page, size));
	}
}
