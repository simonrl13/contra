package com.contra.api.transfer;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	/**
	 * A retry with the same Idempotency-Key and body gets the original transfer back (201 again) and
	 * moves no money. The header is required: without it a client timeout could become a double payment.
	 */
	@PostMapping
	public ResponseEntity<TransferResponse> create(
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
			@Valid @RequestBody TransferRequest request) {
		TransferResponse transfer = transferService.transfer(idempotencyKey, request);
		return ResponseEntity.created(URI.create("/transfers/" + transfer.id())).body(transfer);
	}

	@GetMapping("/{id}")
	public TransferResponse get(@PathVariable UUID id) {
		return transferService.get(id);
	}
}
