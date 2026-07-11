package dev.deschna.scripthub.script.api;

import dev.deschna.scripthub.script.application.InvalidScriptSubmissionException;
import dev.deschna.scripthub.script.application.ScriptExecutionNotFoundException;
import dev.deschna.scripthub.script.domain.InvalidScriptExecutionTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ScriptExecutionExceptionHandler {

    @ExceptionHandler(InvalidScriptSubmissionException.class)
    public ProblemDetail handleInvalidScriptSubmission(InvalidScriptSubmissionException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ScriptExecutionNotFoundException.class)
    public ProblemDetail handleScriptExecutionNotFound(ScriptExecutionNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidScriptExecutionTransitionException.class)
    public ProblemDetail handleInvalidScriptExecutionTransition(
            InvalidScriptExecutionTransitionException exception
    ) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
