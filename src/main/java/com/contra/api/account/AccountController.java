package com.contra.api.account;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
