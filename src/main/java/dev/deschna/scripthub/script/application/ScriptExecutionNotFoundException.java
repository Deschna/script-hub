package dev.deschna.scripthub.script.application;

import java.util.UUID;

public class ScriptExecutionNotFoundException extends RuntimeException {

    public ScriptExecutionNotFoundException(UUID id) {
        super("Script execution not found: " + id);
    }
}
