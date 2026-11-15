package com.chaosblade.transfer.web;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  private ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("success", false, "error", Map.of("code", code, "message", message)));
  }

  @ExceptionHandler(Problem.class)
  public ResponseEntity<?> problem(Problem p) {
    return error(p.status(), p.code(), p.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    String details =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Invalid request");
    return error(400, "INVALID_REQUEST", details);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> unreadable(Exception e) {
    return error(400, "INVALID_REQUEST", "Malformed JSON, wrong value type or unknown field");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missingEndpoint(Exception e) {
    return error(404, "ENDPOINT_NOT_FOUND", "Endpoint is not implemented in this increment");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> wrongMethod(Exception e) {
    return error(405, "METHOD_NOT_ALLOWED", "Use the documented HTTP method");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> internal(Exception e) {
    LOG.error("Defect transfer request failed", e);
    return error(500, "INTERNAL_ERROR", "Request failed; consult the local service log");
  }
}
