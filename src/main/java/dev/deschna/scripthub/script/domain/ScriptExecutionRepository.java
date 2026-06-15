package dev.deschna.scripthub.script.domain;

import java.util.Optional;
import java.util.UUID;

public interface ScriptExecutionRepository {

    void save(ScriptExecution execution);

    Optional<ScriptExecution> findById(UUID id);
}
