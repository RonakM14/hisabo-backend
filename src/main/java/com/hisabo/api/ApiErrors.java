package com.hisabo.api;

import com.hisabo.service.LedgerService.NotFoundException;
import com.hisabo.storage.XmlLedgerStore.*;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException e) {
    return problem(
        400,
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .distinct()
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Invalid request."));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class
  })
  ProblemDetail invalid(Exception e) {
    return problem(
        400,
        e instanceof HttpMessageNotReadableException
            ? "Invalid JSON, date, amount, or category."
            : e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException e) {
    return problem(409, e.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail missing(NotFoundException e) {
    return problem(404, e.getMessage());
  }

  @ExceptionHandler(StorageException.class)
  ProblemDetail storage(StorageException e) {
    return problem(500, e.getMessage());
  }

  private ProblemDetail problem(int status, String detail) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
  }
}
