package dev.deschna.scripthub.script.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryScriptExecutionRepositoryTest {

    private static final String BODY = "console.log('hello')";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");

    private final InMemoryScriptExecutionRepository repository =
            new InMemoryScriptExecutionRepository();

    @Test
    void storesScriptExecution() {
        ScriptExecution execution = createExecution();

        repository.save(execution);

        assertThat(repository.findById(execution.getId())).contains(execution);
    }

    @Test
    void storesDifferentScriptExecutions() {
        ScriptExecution first = ScriptExecution.create("console.log('first')", SUBMITTED_AT);
        ScriptExecution second = ScriptExecution.create("console.log('second')", SUBMITTED_AT);

        repository.save(first);
        repository.save(second);

        assertThat(repository.findById(first.getId())).contains(first);
        assertThat(repository.findById(second.getId())).contains(second);
    }

    @Test
    void returnsEmptyResultForUnknownId() {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000404");

        assertThat(repository.findById(unknownId)).isEmpty();
    }

    @Test
    void rejectsMissingId() {
        assertThatNullPointerException()
                .isThrownBy(() -> repository.findById(null));
    }

    @Test
    void rejectsMissingScriptExecution() {
        assertThatNullPointerException()
                .isThrownBy(() -> repository.save(null));
    }

    private ScriptExecution createExecution() {
        return ScriptExecution.create(BODY, SUBMITTED_AT);
    }
}
