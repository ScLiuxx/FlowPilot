package com.flowpilot.common.util;

/**
 * Converts the identifiers of the HTTP responses into strings.
 *
 * <p>The identifiers are 64 bit snowflake ids. A JavaScript number holds 53 bits exactly, so a
 * browser that reads such an id as a number rounds it: {@code 378902953627938816} becomes
 * {@code 378902953627938800}. The rounded id then addresses a record that does not exist, which
 * is why the detail pages answered with a 404.
 *
 * <p>The string form is the only safe representation for a browser, so every identifier that
 * leaves the API is written as a string.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * Formats an identifier for the HTTP response.
     *
     * @return the identifier as a string, or {@code null} when it is not set
     */
    public static String of(Long id) {
        return id == null ? null : id.toString();
    }
}
