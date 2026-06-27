package dev.deschna.scripthub.script.application;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptExecutionRepository;
import java.time.Clock;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScriptExecutionService {

    @NonNull
    private final ScriptExecutionRepository repository;
    @NonNull
    private final ScriptExecutor scriptExecutor;
    @NonNull
    private final Clock clock;

    public ScriptExecution submit(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidScriptSubmissionException("Script body must not be blank");
        }
        ScriptExecution execution = ScriptExecution.create(body, clock.instant());
        repository.save(execution);
        scriptExecutor.execute(execution);
        return execution;
    }
}
