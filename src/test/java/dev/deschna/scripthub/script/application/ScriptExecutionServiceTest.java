package dev.deschna.scripthub.script.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptExecutionRepository;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ScriptExecutionServiceTest {

    private static final String BODY = "console.log('hello')";
    private static final Instant NOW = Instant.parse("2026-06-14T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID UNKNOWN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000404");

    private final ScriptExecutionRepository repository = mock(ScriptExecutionRepository.class);
    private final ScriptExecutor scriptExecutor = mock(ScriptExecutor.class);
    private final ScriptExecutionService service =
            new ScriptExecutionService(repository, scriptExecutor, CLOCK);

    @Test
    void submitsScriptExecution() {
        ScriptExecution execution = service.submit(BODY);

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getBody()).isEqualTo(BODY);
        assertThat(execution.getSubmittedAt()).isEqualTo(NOW);
        assertThat(execution.getStatus()).isEqualTo(ScriptStatus.QUEUED);
        InOrder inOrder = inOrder(repository, scriptExecutor);
        inOrder.verify(repository).save(same(execution));
        inOrder.verify(scriptExecutor).execute(same(execution));
    }

    @Test
    void getsScriptExecutionById() {
        ScriptExecution execution = ScriptExecution.create(BODY, NOW);
        when(repository.findById(execution.getId())).thenReturn(Optional.of(execution));

        ScriptExecution foundExecution = service.getById(execution.getId());

        assertThat(foundExecution).isSameAs(execution);
        verify(repository).findById(execution.getId());
        verifyNoInteractions(scriptExecutor);
    }

    @Test
    void rejectsUnknownId() {
        when(repository.findById(UNKNOWN_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ScriptExecutionNotFoundException.class)
                .isThrownBy(() -> service.getById(UNKNOWN_ID))
                .withMessage("Script execution not found: " + UNKNOWN_ID);

        verify(repository).findById(UNKNOWN_ID);
        verifyNoInteractions(scriptExecutor);
    }

    @Test
    void rejectsMissingId() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.getById(null));

        verifyNoInteractions(repository, scriptExecutor);
    }

    @Test
    void rejectsMissingBody() {
        assertThatExceptionOfType(InvalidScriptSubmissionException.class)
                .isThrownBy(() -> service.submit(null));

        verifyNoInteractions(repository, scriptExecutor);
    }

    @Test
    void rejectsBlankBody() {
        assertThatExceptionOfType(InvalidScriptSubmissionException.class)
                .isThrownBy(() -> service.submit("  "));

        verifyNoInteractions(repository, scriptExecutor);
    }
}
