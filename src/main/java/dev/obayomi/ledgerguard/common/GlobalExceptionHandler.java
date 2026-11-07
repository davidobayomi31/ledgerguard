package dev.obayomi.ledgerguard.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every error into an RFC 9457 "problem+json" body with a stable {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApi(ApiException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
		problem.setProperty("code", e.code());
		return ResponseEntity.status(e.status()).body(problem);
	}

	/** A database constraint caught something the application didn't: report it, don't leak SQL. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraint(DataIntegrityViolationException e) {
		log.warn("Constraint violation", e);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The request conflicts with existing data.");
		problem.setProperty("code", "CONFLICT");
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
	}
}
