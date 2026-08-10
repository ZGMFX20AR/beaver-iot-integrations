package com.milesight.beaveriot.integrations.irivioc.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Encodes/decodes gateway MQTT payloads.
 * <p>
 * DI/DO channels and our own downlink commands are bare scalars (confirmed live: {@code do/0} carries the
 * literal bytes {@code "1"}/{@code "0"}, no envelope). AI and Modbus poll jobs are NOT - confirmed live they
 * always publish a JSON envelope ({@code {"v":0}} for AI, {@code {"name":...,"value":...,"status":"success"}}
 * for poll jobs) regardless of the poll job's "payload" format field, which - despite the UI offering a
 * Value/JSON/Template choice - isn't even persisted by this firmware (absent from {@code GET /api/config}
 * after being set). So AI/poll values are parsed as JSON here rather than assumed to be bare scalars.
 */
public class ValueCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ValueCodec() {
    }

    public static boolean decodeBoolean(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
    }

    /** AI channel uplink payload: {@code {"v": <number>}}. */
    public static Optional<Double> decodeAi(byte[] payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode v = root.get("v");
            return v != null && v.isNumber() ? Optional.of(v.asDouble()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Modbus poll job uplink payload: {@code {"name":..., "value": <number or [number,...]>, "status": "success"|...}}.
     * Skips (returns empty) on a non-"success" status. A job whose Data Type is "raw" (unscaled/undecoded)
     * reports "value" as an array of raw register words rather than one decoded number - we surface only
     * the first register in that case, since there's no principled single value to derive without a proper
     * Data Type; pick u16/s16/u32/s32/f32 on the job for a fully decoded single reading.
     */
    public static Optional<Double> decodePollJob(byte[] payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode status = root.get("status");
            if (status != null && !"success".equalsIgnoreCase(status.asText())) {
                return Optional.empty();
            }
            JsonNode value = root.get("value");
            if (value == null) {
                return Optional.empty();
            }
            if (value.isArray()) {
                return value.isEmpty() ? Optional.empty() : Optional.of(value.get(0).asDouble());
            }
            return value.isNumber() ? Optional.of(value.asDouble()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static byte[] encode(boolean value) {
        return (value ? "1" : "0").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encode(double value) {
        String text = value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
