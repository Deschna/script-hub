package dev.deschna.scripthub.script.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.deschna.scripthub.script.application.InvalidScriptSubmissionException;
import dev.deschna.scripthub.script.application.ScriptExecutionNotFoundException;
import dev.deschna.scripthub.script.application.ScriptExecutionService;
import dev.deschna.scripthub.script.domain.ScriptExecution;
import dev.deschna.scripthub.script.domain.ScriptStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScriptExecutionController.class)
class ScriptExecutionControllerTest {

    private static final String BODY = "console.log('hello')";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-14T10:15:30Z");
    private static final Instant STARTED_AT = Instant.parse("2026-06-14T10:15:31Z");
    private static final UUID UNKNOWN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScriptExecutionService service;

    @Test
    void submitsScriptExecution() throws Exception {
        ScriptExecution execution = ScriptExecution.create(BODY, SUBMITTED_AT);
        when(service.submit(BODY)).thenReturn(execution);

        mockMvc.perform(post("/scripts")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/scripts/" + execution.getId()))
                .andExpect(jsonPath("$.id").value(execution.getId().toString()))
                .andExpect(jsonPath("$.body").value(BODY))
                .andExpect(jsonPath("$.status").value(ScriptStatus.QUEUED.name()))
                .andExpect(jsonPath("$.submittedAt").value(SUBMITTED_AT.toString()))
                .andExpect(jsonPath("$.standardOutput").value(""))
                .andExpect(jsonPath("$.errorOutput").value(""));
    }

    @Test
    void getsScriptExecutionById() throws Exception {
        ScriptExecution execution = ScriptExecution.create(BODY, SUBMITTED_AT);
        execution.start(STARTED_AT);
        execution.appendStandardOutput("hello\n");
        when(service.getById(execution.getId())).thenReturn(execution);

        mockMvc.perform(get("/scripts/{id}", execution.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(execution.getId().toString()))
                .andExpect(jsonPath("$.body").value(BODY))
                .andExpect(jsonPath("$.status").value(ScriptStatus.RUNNING.name()))
                .andExpect(jsonPath("$.submittedAt").value(SUBMITTED_AT.toString()))
                .andExpect(jsonPath("$.startedAt").value(STARTED_AT.toString()))
                .andExpect(jsonPath("$.standardOutput").value("hello\n"))
                .andExpect(jsonPath("$.errorOutput").value(""));
    }

    @Test
    void returnsBadRequestForInvalidSubmission() throws Exception {
        when(service.submit("  "))
                .thenThrow(new InvalidScriptSubmissionException("Script body must not be blank"));

        mockMvc.perform(post("/scripts")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Script body must not be blank"));
    }

    @Test
    void returnsNotFoundForUnknownScriptExecution() throws Exception {
        when(service.getById(UNKNOWN_ID))
                .thenThrow(new ScriptExecutionNotFoundException(UNKNOWN_ID));

        mockMvc.perform(get("/scripts/{id}", UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Script execution not found: " + UNKNOWN_ID));
    }
}
