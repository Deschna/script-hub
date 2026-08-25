package dev.deschna.scripthub.script.application;

public class ScriptSubmissionTooLargeException extends RuntimeException {

    public ScriptSubmissionTooLargeException(long maxSizeBytes) {
        super("Script body exceeds the maximum size of " + maxSizeBytes + " bytes");
    }
}
