package dev.deschna.scripthub.script.infrastructure.graalvm;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.graalvm.polyglot.Context;
import org.springframework.stereotype.Component;

@Component
class GraalScriptContextRegistry {

    private final ConcurrentMap<UUID, Context> contexts = new ConcurrentHashMap<>();

    void register(UUID executionId, Context context) {
        contexts.put(Objects.requireNonNull(executionId), Objects.requireNonNull(context));
    }

    // The executor owns and closes the Context through try-with-resources.
    // The registry only removes the lookup entry used to stop a running script.
    @SuppressWarnings("resource")
    void unregister(UUID executionId) {
        contexts.remove(Objects.requireNonNull(executionId));
    }

    Optional<Context> findByExecutionId(UUID executionId) {
        return Optional.ofNullable(contexts.get(Objects.requireNonNull(executionId)));
    }
}
