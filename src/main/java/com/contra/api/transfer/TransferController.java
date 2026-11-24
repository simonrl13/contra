package com.contra.api.transfer;

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
@RequestMapping("/transfers")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping
	public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request) {
		TransferResponse transfer = transferService.transfer(request);
		return ResponseEntity.created(URI.create("/transfers/" + transfer.id())).body(transfer);
	}

	@GetMapping("/{id}")
	public TransferResponse get(@PathVariable UUID id) {
		return transferService.get(id);
	}
}
