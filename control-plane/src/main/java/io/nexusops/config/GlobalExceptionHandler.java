package io.nexusops.config;

import io.nexusops.approval.ApprovalStateMachine.IllegalStateTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.NoSuchElementException;

/**
 * Maps exceptions to RFC 7807 problem details. Keeps controllers free of error plumbing
 * and gives clients a consistent, machine-readable error contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Resource not found");
        pd.setType(URI.create("https://nexusops.io/problems/not-found"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation failed");
        pd.setType(URI.create("https://nexusops.io/problems/validation"));
        return pd;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail illegalTransition(IllegalStateTransitionException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Illegal state transition");
        pd.setType(URI.create("https://nexusops.io/problems/state-transition"));
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail illegalState(IllegalStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invalid state");
        pd.setType(URI.create("https://nexusops.io/problems/invalid-state"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Bad request");
        pd.setType(URI.create("https://nexusops.io/problems/bad-request"));
        return pd;
    }
}
