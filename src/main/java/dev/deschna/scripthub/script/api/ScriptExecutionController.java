package dev.deschna.scripthub.script.api;

import dev.deschna.scripthub.script.application.ScriptExecutionService;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import java.net.URI;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scripts")
@RequiredArgsConstructor
public class ScriptExecutionController {

    @NonNull
    private final ScriptExecutionService service;

    @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScriptExecutionResponse> submit(@RequestBody String body) {
        ScriptExecution execution = service.submit(body);
        return ResponseEntity.accepted()
                .location(URI.create("/scripts/" + execution.getId()))
                .body(ScriptExecutionResponse.from(execution));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScriptExecutionResponse getById(@PathVariable UUID id) {
        return ScriptExecutionResponse.from(service.getById(id));
    }
}
