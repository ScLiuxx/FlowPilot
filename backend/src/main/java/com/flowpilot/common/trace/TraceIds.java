package com.flowpilot.common.trace;

import java.security.SecureRandom;

/**
 * Derives the identifiers of a trace.
 *
 * <p>A trace id is 32 hex characters and a span id is 16 hex characters. Those are exactly the
 * formats the OpenTelemetry specification defines, so the ids of this platform can be used as
 * the ids of a real trace without a translation step.
 *
 * <p>The span id is derived from the message id of the envelope, so the same message always
 * produces the same span id. A redelivered message therefore lands in the same span instead of
 * creating a second one.
 */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TraceIds() {
    }

    /**
     * A new random span id.
     */
    public static String newSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return hex(bytes, 8);
    }

    /**
     * The span id of a message.
     *
     * <p>The span id is derived from the message id, so the producer and the consumer of a
     * message agree on the parent span without having to put it into the envelope. The digest
     * is the same one the Python agent service uses, which keeps the span tree of one
     * execution consistent across the process boundary.
     */
    public static String spanIdOf(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return newSpanId();
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(messageId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is part of every JVM, so this cannot happen. A random span id keeps the
            // tracing working even if it did.
            return newSpanId();
        }
    }

    private static String hex(byte[] bytes, int length) {
        char[] out = new char[length * 2];
        for (int i = 0; i < length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0x0F];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0F];
        }
        return new String(out);
    }
}
