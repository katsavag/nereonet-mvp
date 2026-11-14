package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading VesselAPI bodies (P45), leniently: a missing, null or wrongly typed field is simply not shown, never an
 * error. The body itself is kept as received (VesselDataSnapshot.rawJson); nothing here changes it.
 */
public final class VesselJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private VesselJson() {
    }

    /** The parsed body; null when it is empty or not JSON. */
    public static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The body is a JSON object. */
    public static boolean isObject(String json) {
        JsonNode node = parse(json);
        return node != null && node.isObject();
    }

    /** The body indented for reading ("Full VesselAPI response"); as received when it is not JSON. */
    public static String pretty(String json) {
        JsonNode node = parse(json);
        if (node == null) {
            return json;
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /** The object under name, or the node itself when there is none (a body without its wrapper). */
    static JsonNode object(JsonNode root, String name) {
        if (root == null) {
            return null;
        }
        JsonNode inner = root.get(name);
        return inner != null && inner.isObject() ? inner : root;
    }

    /** A text field, stripped; null when missing, null or empty. Numbers are given as text. */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isContainer()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    /** A number field (also a number given as text); null otherwise. */
    static Double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isString()) {
            try {
                return Double.parseDouble(value.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A whole number field; null otherwise. */
    static Long integer(JsonNode node, String field) {
        Double value = number(node, field);
        return value == null || value.isNaN() || value.isInfinite() ? null : Math.round(value);
    }

    /** true only for a JSON true (or "true"). */
    static boolean isTrue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && (value.isBoolean() ? value.booleanValue()
                : value.isString() && "true".equalsIgnoreCase(value.asString().strip()));
    }

    /** false only for a JSON false (or "false"): a missing field is not a "no". */
    static boolean isFalse(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && (value.isBoolean() ? !value.booleanValue()
                : value.isString() && "false".equalsIgnoreCase(value.asString().strip()));
    }

    /** "2026-01-15T12:30:00Z", with an offset, or without one (read as UTC); null for anything else. */
    static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
