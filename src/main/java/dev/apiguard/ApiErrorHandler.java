package dev.apiguard;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e, HttpServletRequest request) {
    return error(e.status(), e.getMessage(), request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST, "Invalid request; check required fields and value types", request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge(Exception e, HttpServletRequest request) {
    return error(HttpStatus.PAYLOAD_TOO_LARGE, "Specification must be at most 2 MB", request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e, HttpServletRequest request) {
    return error(HttpStatus.NOT_FOUND, "Resource not found", request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled request error", e);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
  }

  private ResponseEntity<?> error(HttpStatus status, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "status",
                status.value(),
                "message",
                message,
                "path",
                request.getRequestURI(),
                "timestamp",
                Instant.now().toString()));
  }
}
