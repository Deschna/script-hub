package dev.deschna.scripthub.script.infrastructure.graalvm;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

// Incrementally decodes UTF-8 output, forwarding complete text immediately and
// retaining an incomplete byte sequence until the next write or stream close.
class ScriptExecutionOutputStream extends OutputStream {

    private static final int MAX_UTF8_SEQUENCE_BYTES = 4;

    private final Consumer<String> outputAppender;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    // The decoder leaves an incomplete trailing UTF-8 sequence unread until the next write.
    private final byte[] pendingBytes = new byte[MAX_UTF8_SEQUENCE_BYTES];
    // Reused by write(int) to avoid allocating a one-byte array for every call.
    private final byte[] singleByte = new byte[1];

    private int pendingByteCount;
    private boolean closed;

    ScriptExecutionOutputStream(Consumer<String> outputAppender) {
        this.outputAppender = Objects.requireNonNull(outputAppender);
    }

    @Override
    public void write(int value) {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        ensureOpen();
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return;
        }

        ByteBuffer input = inputWithPendingBytes(buffer, offset, length);
        decode(input, false);
        retainPendingBytes(input);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        ByteBuffer input = ByteBuffer.wrap(pendingBytes, 0, pendingByteCount);
        decode(input, true);
        pendingByteCount = 0;
        flushDecoder();
    }

    private ByteBuffer inputWithPendingBytes(byte[] buffer, int offset, int length) {
        if (pendingByteCount == 0) {
            return ByteBuffer.wrap(buffer, offset, length);
        }

        byte[] combined = new byte[pendingByteCount + length];
        System.arraycopy(pendingBytes, 0, combined, 0, pendingByteCount);
        System.arraycopy(buffer, offset, combined, pendingByteCount, length);
        pendingByteCount = 0;
        return ByteBuffer.wrap(combined);
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        CharBuffer output = CharBuffer.allocate(Math.max(1, input.remaining()));
        CoderResult result = decoder.decode(input, output, endOfInput);
        requireUnderflow(result);
        append(output);
    }

    private void flushDecoder() {
        CharBuffer output = CharBuffer.allocate(1);
        CoderResult result = decoder.flush(output);
        requireUnderflow(result);
        append(output);
    }

    private void append(CharBuffer output) {
        output.flip();
        if (output.hasRemaining()) {
            outputAppender.accept(output.toString());
        }
    }

    private void retainPendingBytes(ByteBuffer input) {
        pendingByteCount = input.remaining();
        input.get(pendingBytes, 0, pendingByteCount);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Output stream is closed");
        }
    }

    private void requireUnderflow(CoderResult result) {
        if (!result.isUnderflow()) {
            throw new IllegalStateException("UTF-8 decoder did not consume the output chunk");
        }
    }
}
