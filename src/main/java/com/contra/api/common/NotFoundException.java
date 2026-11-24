package com.contra.api.common;

import java.util.UUID;

/** Thrown by services when a resource doesn't exist. Mapped to 404 by GlobalExceptionHandler. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String resource, UUID id) {
		super(resource + " " + id + " not found");
	}
}
