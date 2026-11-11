package com.flowpilot.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON helpers shared across modules.
 *
 * <p>The mapper used here is also the one configured on the HTTP layer, so what is written into
 * a JSON column and what is returned over HTTP are structurally identical.
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize value to JSON", ex);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize JSON to " + type.getSimpleName(), ex);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize JSON", ex);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JSON object", ex);
        }
    }

    public static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof String string) {
            return toMap(string);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.convertValue(value, Map.class);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to convert value to map", ex);
        }
    }

    public static ObjectNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Canonicalises a JSON document so that two semantically identical definitions always produce
     * the same hash. Object keys are sorted recursively; array order is preserved because it is
     * meaningful in a workflow definition.
     */
    public static String canonicalize(Object value) {
        try {
            return MAPPER.writeValueAsString(canonicalValue(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize value", ex);
        }
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            iterable.forEach(item -> list.add(canonicalValue(item)));
            return list;
        }
        return value;
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0x0F, 16));
                builder.append(Character.forDigit(b & 0x0F, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
