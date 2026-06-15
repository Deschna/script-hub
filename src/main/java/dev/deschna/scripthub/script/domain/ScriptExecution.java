package dev.deschna.scripthub.script.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;

public class ScriptExecution {

    @Getter
    private final UUID id;
    @Getter
    private final String body;
    @Getter
    private final Instant submittedAt;
    @Getter
    private volatile ScriptStatus status;
    @Getter
    private volatile Instant startedAt;
    @Getter
    private volatile Instant finishedAt;
    @Getter
    private volatile String errorStackTrace;

    private final StringBuilder standardOutput = new StringBuilder();
    private final StringBuilder errorOutput = new StringBuilder();

    private ScriptExecution(UUID id, String body, Instant submittedAt) {
        this.id = Objects.requireNonNull(id);
        this.body = Objects.requireNonNull(body);
        this.submittedAt = Objects.requireNonNull(submittedAt);
        this.status = ScriptStatus.QUEUED;
    }

    public static ScriptExecution create(String body, Instant submittedAt) {
        return new ScriptExecution(UUID.randomUUID(), body, submittedAt);
    }

    public synchronized void start(Instant startedAt) {
        Objects.requireNonNull(startedAt);
        requireStatus(ScriptStatus.QUEUED);
        requireNotBefore(startedAt, submittedAt);
        this.startedAt = startedAt;
        status = ScriptStatus.RUNNING;
    }

    public synchronized void complete(Instant finishedAt) {
        Objects.requireNonNull(finishedAt);
        requireStatus(ScriptStatus.RUNNING);
        requireNotBefore(finishedAt, startedAt);
        this.finishedAt = finishedAt;
        status = ScriptStatus.COMPLETED;
    }

    public synchronized void fail(Instant finishedAt, String errorStackTrace) {
        Objects.requireNonNull(finishedAt);
        Objects.requireNonNull(errorStackTrace);
        requireStatus(ScriptStatus.RUNNING);
        requireNotBefore(finishedAt, startedAt);
        this.finishedAt = finishedAt;
        this.errorStackTrace = errorStackTrace;
        status = ScriptStatus.FAILED;
    }

    public synchronized void stop(Instant finishedAt) {
        Objects.requireNonNull(finishedAt);
        if (status != ScriptStatus.QUEUED && status != ScriptStatus.RUNNING) {
            throw invalidTransition();
        }
        requireNotBefore(finishedAt, startedAt == null ? submittedAt : startedAt);
        this.finishedAt = finishedAt;
        status = ScriptStatus.STOPPED;
    }

    public synchronized void appendStandardOutput(String output) {
        Objects.requireNonNull(output);
        requireStatus(ScriptStatus.RUNNING);
        standardOutput.append(output);
    }

    public synchronized void appendErrorOutput(String output) {
        Objects.requireNonNull(output);
        requireStatus(ScriptStatus.RUNNING);
        errorOutput.append(output);
    }

    public synchronized String getStandardOutput() {
        return standardOutput.toString();
    }

    public synchronized String getErrorOutput() {
        return errorOutput.toString();
    }

    public Optional<Duration> getDuration() {
        if (startedAt == null || finishedAt == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(startedAt, finishedAt));
    }

    private void requireStatus(ScriptStatus expectedStatus) {
        if (status != expectedStatus) {
            throw invalidTransition();
        }
    }

    private void requireNotBefore(Instant actual, Instant expectedMinimum) {
        if (actual.isBefore(expectedMinimum)) {
            throw new IllegalArgumentException("Timestamp must not go backwards");
        }
    }

    private IllegalStateException invalidTransition() {
        return new IllegalStateException("Invalid script execution status transition");
    }
}
