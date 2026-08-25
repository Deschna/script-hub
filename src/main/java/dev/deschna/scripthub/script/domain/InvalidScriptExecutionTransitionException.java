package dev.deschna.scripthub.script.domain;

import java.util.Arrays;
import java.util.stream.Collectors;

public class InvalidScriptExecutionTransitionException extends RuntimeException {

    public InvalidScriptExecutionTransitionException(
            ScriptStatus currentStatus,
            ScriptStatus... expectedStatuses
    ) {
        super("Invalid script execution status: "
                + currentStatus + ", expected: " + formatExpectedStatuses(expectedStatuses));
    }

    private static String formatExpectedStatuses(ScriptStatus[] expectedStatuses) {
        return Arrays.stream(expectedStatuses)
                .map(ScriptStatus::name)
                .collect(Collectors.joining(", "));
    }
}
