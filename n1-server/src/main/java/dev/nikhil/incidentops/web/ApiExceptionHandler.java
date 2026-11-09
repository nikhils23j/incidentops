package dev.nikhil.incidentops.web;

import dev.nikhil.incidentops.domain.IllegalTransitionException;
import dev.nikhil.incidentops.domain.PostmortemRequiredException;
import dev.nikhil.incidentops.service.NotFoundException;
import dev.nikhil.incidentops.service.VersionConflictException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as RFC 7807 application/problem+json with a stable "type" the UI can switch on. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("/problems/" + type));
        pd.setTitle(title);
        return pd;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Incident not found", e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException e) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "illegal-transition", "Illegal status transition", e.getMessage());
        pd.setProperty("currentStatus", e.from());
        pd.setProperty("allowedNext", e.from().allowedNext());
        return pd;
    }

    @ExceptionHandler(VersionConflictException.class)
    ProblemDetail versionConflict(VersionConflictException e) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "version-conflict", "Someone else changed this incident", e.getMessage());
        pd.setProperty("currentVersion", e.currentVersion());
        return pd;
    }

    /** Two requests passed the version check at the same time; Hibernate's "where version=?" caught the loser. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail lostRace(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "version-conflict", "Someone else changed this incident",
            "Concurrent update detected; reload and try again");
    }

    @ExceptionHandler(PostmortemRequiredException.class)
    ProblemDetail postmortemRequired(PostmortemRequiredException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "postmortem-required", "Postmortem required", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation", "Invalid request", "One or more fields are invalid");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }
}
