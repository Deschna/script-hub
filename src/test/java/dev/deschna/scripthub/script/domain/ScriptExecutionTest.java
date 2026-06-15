package dev.deschna.scripthub.script.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScriptExecutionTest {

    private static final String BODY = "console.log('hello')";
    private static final String ERROR_STACK_TRACE =
            "ReferenceError: value is not defined at script.js:1";
    private static final Instant BEFORE_SUBMISSION = Instant.parse("2026-06-14T10:15:29Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");
    private static final Instant STARTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-06-14T10:15:35Z");
    private static final Duration DURATION = Duration.between(STARTED_AT, FINISHED_AT);

    @Test
    void createsQueuedScriptExecution() {
        ScriptExecution execution = ScriptExecution.create(BODY, SUBMITTED_AT);

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getBody()).isEqualTo(BODY);
        assertThat(execution.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.QUEUED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getErrorStackTrace()).isNull();
        assertThat(execution.getStandardOutput()).isEmpty();
        assertThat(execution.getErrorOutput()).isEmpty();
        assertThat(execution.getDuration()).isEmpty();
    }

    @Test
    void createsUniqueIds() {
        ScriptExecution first = createExecution();
        ScriptExecution second = createExecution();

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void rejectsMissingBody() {
        assertThatNullPointerException()
                .isThrownBy(() -> ScriptExecution.create(null, SUBMITTED_AT));
    }

    @Test
    void rejectsMissingSubmittedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> ScriptExecution.create(BODY, null));
    }

    @Test
    void startsQueuedScriptExecution() {
        ScriptExecution execution = createExecution();

        execution.start(STARTED_AT);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.RUNNING);
        assertThat(execution.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getDuration()).isEmpty();
    }

    @Test
    void rejectsMissingStartedAt() {
        ScriptExecution execution = createExecution();

        assertThatNullPointerException()
                .isThrownBy(() -> execution.start(null));
    }

    @Test
    void rejectsStartingBeforeSubmission() {
        ScriptExecution execution = createExecution();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> execution.start(BEFORE_SUBMISSION));
    }

    @Test
    void completesRunningScriptExecution() {
        ScriptExecution execution = createExecution();

        execution.start(STARTED_AT);
        execution.complete(FINISHED_AT);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.COMPLETED);
        assertThat(execution.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(execution.getDuration()).contains(DURATION);
    }

    @Test
    void rejectsMissingFinishedAtWhenCompleting() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> execution.complete(null));
    }

    @Test
    void rejectsCompletingQueuedScriptExecution() {
        ScriptExecution execution = createExecution();

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.complete(FINISHED_AT));
    }

    @Test
    void rejectsCompletingBeforeStart() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> execution.complete(SUBMITTED_AT));
    }

    @Test
    void failsRunningScriptExecution() {
        ScriptExecution execution = createExecution();

        execution.start(STARTED_AT);
        execution.fail(FINISHED_AT, ERROR_STACK_TRACE);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.FAILED);
        assertThat(execution.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(execution.getErrorStackTrace()).isEqualTo(ERROR_STACK_TRACE);
        assertThat(execution.getDuration()).contains(DURATION);
    }

    @Test
    void rejectsMissingFinishedAtWhenFailing() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> execution.fail(null, ERROR_STACK_TRACE));
    }

    @Test
    void rejectsMissingErrorStackTrace() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> execution.fail(FINISHED_AT, null));
    }

    @Test
    void rejectsFailingQueuedScriptExecution() {
        ScriptExecution execution = createExecution();

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.fail(FINISHED_AT, ERROR_STACK_TRACE));
    }

    @Test
    void rejectsFailingBeforeStart() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> execution.fail(SUBMITTED_AT, ERROR_STACK_TRACE));
    }

    @Test
    void stopsQueuedScriptExecution() {
        ScriptExecution execution = createExecution();

        execution.stop(FINISHED_AT);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.STOPPED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(execution.getDuration()).isEmpty();
    }

    @Test
    void stopsRunningScriptExecution() {
        ScriptExecution execution = createExecution();

        execution.start(STARTED_AT);
        execution.stop(FINISHED_AT);

        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.STOPPED);
        assertThat(execution.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(execution.getDuration()).contains(DURATION);
    }

    @Test
    void rejectsMissingFinishedAtWhenStopping() {
        ScriptExecution execution = createExecution();

        assertThatNullPointerException()
                .isThrownBy(() -> execution.stop(null));
    }

    @Test
    void rejectsStoppingQueuedExecutionBeforeSubmission() {
        ScriptExecution execution = createExecution();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> execution.stop(BEFORE_SUBMISSION));
    }

    @Test
    void rejectsChangingCompletedScriptExecution() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);
        execution.complete(FINISHED_AT);

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.stop(FINISHED_AT));
    }

    @Test
    void appendsStandardOutput() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        execution.appendStandardOutput("hello");
        execution.appendStandardOutput(System.lineSeparator());
        execution.appendStandardOutput("world");

        assertThat(execution.getStandardOutput())
                .isEqualTo("hello" + System.lineSeparator() + "world");
    }

    @Test
    void rejectsMissingStandardOutput() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> execution.appendStandardOutput(null));
    }

    @Test
    void rejectsAppendingStandardOutputBeforeStart() {
        ScriptExecution execution = createExecution();

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.appendStandardOutput("hello"));
    }

    @Test
    void rejectsAppendingStandardOutputAfterCompletion() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);
        execution.complete(FINISHED_AT);

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.appendStandardOutput("late output"));
    }

    @Test
    void appendsErrorOutput() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        execution.appendErrorOutput("warning");
        execution.appendErrorOutput(System.lineSeparator());
        execution.appendErrorOutput("failed");

        assertThat(execution.getErrorOutput())
                .isEqualTo("warning" + System.lineSeparator() + "failed");
    }

    @Test
    void rejectsMissingErrorOutput() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> execution.appendErrorOutput(null));
    }

    @Test
    void rejectsAppendingErrorOutputBeforeStart() {
        ScriptExecution execution = createExecution();

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.appendErrorOutput("error"));
    }

    @Test
    void rejectsAppendingErrorOutputAfterCompletion() {
        ScriptExecution execution = createExecution();
        execution.start(STARTED_AT);
        execution.complete(FINISHED_AT);

        assertThatIllegalStateException()
                .isThrownBy(() -> execution.appendErrorOutput("late error"));
    }

    private ScriptExecution createExecution() {
        return ScriptExecution.create(BODY, SUBMITTED_AT);
    }
}
