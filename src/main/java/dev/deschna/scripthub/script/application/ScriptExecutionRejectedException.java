package dev.deschna.scripthub.script.application;

public class ScriptExecutionRejectedException extends RuntimeException {

    public ScriptExecutionRejectedException(Throwable cause) {
        super("Script execution is temporarily unavailable", cause);
    }
}
