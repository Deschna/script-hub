package dev.deschna.scripthub.script.application;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

@Service
public class ScriptExecutionService {

    private final ScriptExecutionRepository repository;
    private final ScriptExecutor scriptExecutor;
    private final Clock clock;
    private final long maxScriptSizeBytes;

    public ScriptExecutionService(
            ScriptExecutionRepository repository,
            ScriptExecutor scriptExecutor,
            Clock clock,
            @Value("${script-hub.execution.submission.max-size}") DataSize maxScriptSize
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.scriptExecutor = Objects.requireNonNull(scriptExecutor);
        this.clock = Objects.requireNonNull(clock);
        this.maxScriptSizeBytes = requirePositiveByteCount(maxScriptSize);
    }

    public ScriptExecution submit(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidScriptSubmissionException("Script body must not be blank");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > maxScriptSizeBytes) {
            throw new ScriptSubmissionTooLargeException(maxScriptSizeBytes);
        }
        ScriptExecution execution = ScriptExecution.create(body, clock.instant());
        repository.save(execution);
        try {
            scriptExecutor.execute(execution);
        } catch (ScriptExecutionRejectedException exception) {
            repository.deleteById(execution.getId());
            throw exception;
        }
        return execution;
    }

    public ScriptExecution getById(UUID id) {
        Objects.requireNonNull(id);
        return repository.findById(id)
                .orElseThrow(() -> new ScriptExecutionNotFoundException(id));
    }

    public ScriptExecution stop(UUID id) {
        ScriptExecution execution = getById(id);
        execution.stop(clock.instant());
        scriptExecutor.stop(execution);
        return execution;
    }

    private static long requirePositiveByteCount(DataSize maxScriptSize) {
        Objects.requireNonNull(maxScriptSize);
        long maxScriptSizeBytes = maxScriptSize.toBytes();
        if (maxScriptSizeBytes <= 0) {
            throw new IllegalArgumentException("Maximum script size must be positive");
        }
        return maxScriptSizeBytes;
    }
}
