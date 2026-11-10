package dev.studyplanner.common;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail application(ApiException exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    ProblemDetail malformed(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The resource already exists or conflicts with current data");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missing() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Endpoint not found");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse response) {
            return response.getBody();
        }
        log.error("Unhandled request failure", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
