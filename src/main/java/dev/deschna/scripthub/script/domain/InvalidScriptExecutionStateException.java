package dev.deschna.scripthub.script.domain;

public class InvalidScriptExecutionStateException extends RuntimeException {

    public InvalidScriptExecutionStateException(
            ScriptStatus currentStatus,
            ScriptStatus requiredStatus
    ) {
        super("Invalid script execution status: "
                + currentStatus + ", expected: " + requiredStatus);
    }
}
