package dev.deschna.scripthub.script.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptExecutionRepository;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ScriptExecutionServiceTest {

    private static final String BODY = "console.log('hello')";
    private static final Instant NOW = Instant.parse("2026-06-14T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ScriptExecutionRepository repository = mock(ScriptExecutionRepository.class);
    private final ScriptExecutionService service = new ScriptExecutionService(repository, CLOCK);

    @Test
    void submitsQueuedScriptExecution() {
        ScriptExecution execution = service.submit(BODY);

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getBody()).isEqualTo(BODY);
        assertThat(execution.getSubmittedAt()).isEqualTo(NOW);
        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.QUEUED);
        verify(repository).save(same(execution));
    }

    @Test
    void rejectsMissingBody() {
        assertThatExceptionOfType(InvalidScriptSubmissionException.class)
                .isThrownBy(() -> service.submit(null));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankBody() {
        assertThatExceptionOfType(InvalidScriptSubmissionException.class)
                .isThrownBy(() -> service.submit("  "));

        verifyNoInteractions(repository);
    }
}
