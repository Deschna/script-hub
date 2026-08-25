package dev.deschna.scripthub.script.api;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record ScriptExecutionResponse(
        UUID id,
        String body,
        ScriptStatus status,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMillis,
        String standardOutput,
        String errorOutput,
        String errorStackTrace
) {

    public static ScriptExecutionResponse from(ScriptExecution execution) {
        return new ScriptExecutionResponse(
                execution.getId(),
                execution.getBody(),
                execution.getStatus(),
                execution.getSubmittedAt(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getDuration()
                        .map(Duration::toMillis)
                        .orElse(null),
                execution.getStandardOutput(),
                execution.getErrorOutput(),
                execution.getErrorStackTrace()
        );
    }
}
