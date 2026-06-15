package dev.deschna.scripthub.script.infrastructure;

import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptExecutionRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryScriptExecutionRepository implements ScriptExecutionRepository {

    private final ConcurrentMap<UUID, ScriptExecution> executions = new ConcurrentHashMap<>();

    @Override
    public void save(ScriptExecution execution) {
        Objects.requireNonNull(execution);
        executions.put(execution.getId(), execution);
    }

    @Override
    public Optional<ScriptExecution> findById(UUID id) {
        return Optional.ofNullable(executions.get(Objects.requireNonNull(id)));
    }
}
