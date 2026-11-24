package com.contra.api.common;

/** The request clashed with another one running at the same time. Mapped to 409; the client may retry. */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}
