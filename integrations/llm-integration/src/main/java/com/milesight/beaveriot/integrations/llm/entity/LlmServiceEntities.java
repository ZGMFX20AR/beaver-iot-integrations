package com.milesight.beaveriot.integrations.llm.entity;

import com.milesight.beaveriot.context.integration.entity.annotation.Attribute;
import com.milesight.beaveriot.context.integration.entity.annotation.Entities;
import com.milesight.beaveriot.context.integration.entity.annotation.Entity;
import com.milesight.beaveriot.context.integration.entity.annotation.IntegrationEntities;
import com.milesight.beaveriot.context.integration.enums.EntityType;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.integrations.llm.enums.LlmModel;
import lombok.*;

/**
 * @Author yuanh
 * @Description
 * @Package com.milesight.beaveriot.integrations.llm.entity
 * @Date 2025/2/7 14:03
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IntegrationEntities
public class LlmServiceEntities extends ExchangePayload {

    @Entity(type = EntityType.SERVICE, name = "Test connection")
    private TestConnection testConnection;

    @Entity(type = EntityType.SERVICE, name = "Generate a completion")
    private GenerateCompletion generateCompletion;

    @Entity(type = EntityType.SERVICE, name = "Start local Ollama server",
            description = "Starts the hailo-ollama server bundled in this container. Requires the Hailo device to be passed through - see beaver-iot-npu.dockerfile.")
    private StartLocalServer startLocalServer;

    @Entity(type = EntityType.SERVICE, name = "Stop local Ollama server")
    private StopLocalServer stopLocalServer;

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Entities
    public static class GenerateCompletion extends ExchangePayload {
        @Entity(attributes = {@Attribute(minLength = 1, enumClass = LlmModel.class)})
        private String model;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String prompt;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String suffix;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String images;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String format;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String options;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String system;
        @Entity(attributes = {@Attribute(minLength = 1, optional = true)})
        private String template;
        @Entity(attributes = {@Attribute(optional = true)})
        private Boolean row;
        @Entity(attributes = {@Attribute(optional = true)})
        private Integer keepAlive;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @NoArgsConstructor
    @Entities
    public static class TestConnection extends ExchangePayload {
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @NoArgsConstructor
    @Entities
    public static class StartLocalServer extends ExchangePayload {
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @NoArgsConstructor
    @Entities
    public static class StopLocalServer extends ExchangePayload {
    }
}
