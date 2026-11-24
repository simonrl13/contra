package com.contra.api.common;

import java.util.Map;
import java.util.TreeMap;

import com.contra.api.transfer.TransferRejectedException;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Single place that turns exceptions into HTTP error responses.
 *
 * Extending ResponseEntityExceptionHandler makes Spring MVC's own exceptions (validation failure,
 * malformed JSON, unknown route, wrong method...) return RFC 9457 ProblemDetail JSON.
 * Domain exceptions stay HTTP-agnostic and are mapped to a status here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	/** Well-formed request that breaks a business rule: 422, with a machine-readable reason. */
	@ExceptionHandler(TransferRejectedException.class)
	ProblemDetail handleTransferRejected(TransferRejectedException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
		problem.setProperty("reason", exception.getReason().name());
		return problem;
	}

	/** Bean Validation failure on a @Valid request body: 400, listing every invalid field. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new TreeMap<>(); // sorted, so the response is stable
		for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
			errors.put(fieldError.getField(), fieldError.getDefaultMessage());
		}
		return validationProblem(status, errors);
	}

	/**
	 * Method validation failure: a constraint on a header or query parameter (Idempotency-Key, page,
	 * size). Once a controller method has such a constraint, Spring MVC validates the whole method in one
	 * go, so an invalid @Valid body also arrives here instead of in handleMethodArgumentNotValid.
	 * Same response shape either way: field or parameter name → message.
	 */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new TreeMap<>();
		for (ParameterValidationResult result : exception.getParameterValidationResults()) {
			if (result instanceof ParameterErrors bodyErrors) {
				for (FieldError fieldError : bodyErrors.getFieldErrors()) {
					errors.put(fieldError.getField(), fieldError.getDefaultMessage());
				}
			} else {
				for (MessageSourceResolvable error : result.getResolvableErrors()) {
					errors.put(parameterName(result.getMethodParameter()), error.getDefaultMessage());
				}
			}
		}
		return validationProblem(status, errors);
	}

	/** The header name as the client sent it ("Idempotency-Key"), otherwise the Java parameter name. */
	private static String parameterName(MethodParameter parameter) {
		RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
		return header != null ? header.value() : parameter.getParameterName();
	}

	/**
	 * Body that can't be turned into the request record: broken JSON, or a value of the wrong type
	 * such as an unknown currency code. When Jackson knows which field failed, name it the same way
	 * as a validation error. Jackson's own message isn't returned: it exposes internal class names.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (exception.getCause() instanceof JacksonException jacksonException && !jacksonException.getPath().isEmpty()) {
			String field = jacksonException.getPath().getLast().getPropertyName();
			return validationProblem(status, Map.of(field, "invalid value"));
		}
		return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, "Malformed JSON request"));
	}

	private ResponseEntity<Object> validationProblem(HttpStatusCode status, Map<String, String> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
		problem.setProperty("errors", errors);
		return ResponseEntity.status(status).body(problem);
	}
}
