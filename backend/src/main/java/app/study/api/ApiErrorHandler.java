package app.study.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/** Turns exceptions into a small JSON body the UI can display verbatim. */
@RestControllerAdvice(basePackages = "app.study.api")
public class ApiErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
		return body(HttpStatus.valueOf(e.getStatusCode().value()),
				e.getReason() != null ? e.getReason() : e.getMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
		return body(HttpStatus.PAYLOAD_TOO_LARGE, "That upload is larger than the configured limit.");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> generic(Exception e) {
		log.error("Unhandled API error", e);
		return body(HttpStatus.INTERNAL_SERVER_ERROR,
				e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
	}

	private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", message));
	}
}
