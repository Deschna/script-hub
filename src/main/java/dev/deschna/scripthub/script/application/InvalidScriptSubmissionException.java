package dev.deschna.scripthub.script.application;

public class InvalidScriptSubmissionException extends RuntimeException {

    public InvalidScriptSubmissionException(String message) {
        super(message);
    }
}
