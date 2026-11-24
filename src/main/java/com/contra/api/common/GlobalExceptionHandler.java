package com.contra.api.common;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into HTTP error responses.
 *
 * Extending ResponseEntityExceptionHandler makes Spring MVC's own exceptions (validation failure,
 * malformed JSON, unknown route, wrong method...) return RFC 9457 ProblemDetail JSON.
 * Handlers for our domain exceptions (404, 409, 422) are added here in later stages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
}
