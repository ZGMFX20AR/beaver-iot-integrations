package com.milesight.beaveriot.integrations.milesightgateway.model.request;

import lombok.Data;

import java.util.List;

/**
 * Definition of a custom (non-blueprint) LoRaWAN device model.
 * <p>
 * Lets a device that is not published in the blueprint library be onboarded: the entity
 * list describes what the device reports, and the codec turns its binary uplink into the
 * JSON those entities are mapped from.
 *
 * @author cytron
 */
@Data
public class CustomDeviceModelRequest {
    /**
     * Display name of the model, shown in the gateway's device model picker.
     */
    private String name;

    private String description;

    /**
     * LoRaWAN device class, used to pick the matching profile on the gateway.
     * One of {@code ClassA}, {@code ClassB}, {@code ClassC}.
     */
    private String loraClass;

    /**
     * JavaScript source containing the decoder (and optionally the encoder).
     */
    private String codecCode;

    /**
     * Decoder entry function name within {@link #codecCode}.
     */
    private String codecEntry;

    /**
     * Optional encoder entry function name; leave unset for uplink-only devices.
     */
    private String codecEncodeEntry;

    /**
     * Entities the device reports. Each becomes both an entity on the device and an
     * input mapping from the decoded JSON.
     */
    private List<EntityDefinition> entities;

    @Data
    public static class EntityDefinition {
        /**
         * Key in the decoded JSON, also used as the entity identifier.
         */
        private String identifier;

        /**
         * Human readable entity name.
         */
        private String name;

        /**
         * One of {@code STRING}, {@code LONG}, {@code DOUBLE}, {@code BOOLEAN}.
         */
        private String valueType;

        /**
         * Optional unit shown alongside the value.
         */
        private String unit;

        /**
         * Label shown in place of a raw {@code true} for a BOOLEAN entity, e.g. "Alarm".
         * Ignored for every other value type.
         * <p>
         * Dashboard widgets key their per-state label, icon and colour off an entity's
         * {@code enum} attribute rather than off its value type, so a boolean without one
         * renders as a bare true/false with a single appearance. This and
         * {@link #falseLabel} are what let a custom model declare that enum.
         */
        private String trueLabel;

        /**
         * Label shown in place of a raw {@code false} for a BOOLEAN entity, e.g. "Normal".
         * See {@link #trueLabel}.
         */
        private String falseLabel;
    }
}
