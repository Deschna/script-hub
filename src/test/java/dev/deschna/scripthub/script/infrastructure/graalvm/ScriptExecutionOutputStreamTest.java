package dev.deschna.scripthub.script.infrastructure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ScriptExecutionOutputStreamTest {

    @Test
    void decodesUtf8SequenceSplitAcrossWrites() {
        StringBuilder output = new StringBuilder();
        byte[] bytes = "A👋Б".getBytes(StandardCharsets.UTF_8);

        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(output::append)) {
            stream.write(bytes, 0, 3);
            assertThat(output).hasToString("A");

            stream.write(bytes, 3, bytes.length - 3);

            assertThat(output).hasToString("A👋Б");
        }
    }

    @Test
    void decodesUtf8WrittenOneByteAtATime() {
        StringBuilder output = new StringBuilder();

        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(output::append)) {
            for (byte value : "こんにちは 👋".getBytes(StandardCharsets.UTF_8)) {
                stream.write(value);
            }

            assertThat(output).hasToString("こんにちは 👋");
        }
    }

    @Test
    void decodesOnlyRequestedBufferRange() {
        StringBuilder output = new StringBuilder();
        byte[] bytes = "ignoreПриветignore".getBytes(StandardCharsets.UTF_8);
        int offset = "ignore".getBytes(StandardCharsets.UTF_8).length;
        int length = "Привет".getBytes(StandardCharsets.UTF_8).length;

        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(output::append)) {
            stream.write(bytes, offset, length);

            assertThat(output).hasToString("Привет");
        }
    }

    @Test
    void ignoresEmptyWrite() {
        @SuppressWarnings("unchecked")
        Consumer<String> outputAppender = mock(Consumer.class);

        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(outputAppender)) {
            stream.write(new byte[0], 0, 0);

            verifyNoInteractions(outputAppender);
        }
    }

    @Test
    void rejectsInvalidBufferRange() {
        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(ignored -> { })) {
            assertThatIndexOutOfBoundsException()
                    .isThrownBy(() -> stream.write(new byte[1], 1, 1));
        }
    }

    @Test
    void replacesIncompleteUtf8SequenceWhenClosed() {
        StringBuilder output = new StringBuilder();
        byte[] bytes = "€".getBytes(StandardCharsets.UTF_8);

        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(output::append)) {
            stream.write(bytes, 0, bytes.length - 1);
            assertThat(output).isEmpty();

            stream.close();

            assertThat(output).hasToString("�");
        }
    }

    @Test
    void rejectsWritesAfterClose() {
        try (ScriptExecutionOutputStream stream =
                new ScriptExecutionOutputStream(ignored -> { })) {
            stream.close();

            assertThatIllegalStateException()
                    .isThrownBy(() -> stream.write('a'))
                    .withMessage("Output stream is closed");
        }
    }
}
