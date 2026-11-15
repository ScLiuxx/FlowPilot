package com.flowpilot.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Formats the timestamps of the API.
 *
 * <p>The whole platform stores its timestamps in UTC: the JVM runs with the UTC time zone, the
 * database server runs with {@code +00:00} and the JDBC connection asks for UTC. A
 * {@link LocalDateTime} therefore carries the wall clock of UTC.
 *
 * <p>A wall clock without a zone is ambiguous. A browser reads {@code 2026-11-15T10:23:45.123} as
 * <em>local</em> time, so a user in UTC+8 would see the execution eight hours in the past, and the
 * running duration would be eight hours too long while the closed duration would be right again.
 * The offset therefore has to be part of every timestamp that leaves the backend.
 */
public final class Timestamps {

    /** ISO-8601 with an explicit UTC offset and millisecond precision. */
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private Timestamps() {
    }

    /**
     * The timestamp as an ISO-8601 instant in UTC.
     *
     * <p>The value is a wall clock of UTC, so it is labelled as such. A {@code null} stays
     * {@code null}: the field simply has no value yet.
     */
    public static String toIso(LocalDateTime value) {
        return value == null ? null : ISO_UTC.format(value);
    }
}
