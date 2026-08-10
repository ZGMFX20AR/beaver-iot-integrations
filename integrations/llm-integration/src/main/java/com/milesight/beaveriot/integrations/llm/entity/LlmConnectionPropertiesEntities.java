package com.milesight.beaveriot.integrations.llm.entity;

import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.context.integration.entity.annotation.Attribute;
import com.milesight.beaveriot.context.integration.entity.annotation.Entities;
import com.milesight.beaveriot.context.integration.entity.annotation.Entity;
import com.milesight.beaveriot.context.integration.entity.annotation.IntegrationEntities;
import com.milesight.beaveriot.context.integration.enums.AccessMod;
import com.milesight.beaveriot.context.integration.enums.EntityType;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.integrations.llm.constant.LlmIntegrationConstants;
import com.milesight.beaveriot.integrations.llm.enums.LlmProvider;
import lombok.*;
import lombok.experimental.FieldNameConstants;

@FieldNameConstants
@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IntegrationEntities
public class LlmConnectionPropertiesEntities extends ExchangePayload {

    public static String getKey(String propertyKey) {
        return LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration." + StringUtils.toSnakeCase(propertyKey);
    }

    @Entity(type = EntityType.PROPERTY, name = "LLM Properties")
    private LlmProperties llmProperties;


    @Entity(type = EntityType.PROPERTY, name = "LLM api status", accessMod = AccessMod.R)
    private Boolean apiStatus;

    @Entity(type = EntityType.PROPERTY, name = "LLM models", accessMod = AccessMod.R)
    private String models;

    @Entity(type = EntityType.PROPERTY, name = "Local Ollama server running", accessMod = AccessMod.R,
            description = "Whether the hailo-ollama server bundled in this container is currently running")
    private Boolean localServerRunning;


    @FieldNameConstants
    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Entities
    public static class LlmProperties extends ExchangePayload {

        @Entity(type = EntityType.PROPERTY, name = "Provider", accessMod = AccessMod.RW,
                description = "Which LLM backend to call",
                attributes = {@Attribute(enumClass = LlmProvider.class)})
        private String providerType;

        @Entity(type = EntityType.PROPERTY, name = "Base URL", accessMod = AccessMod.RW,
                description = "Leave blank for Ollama to auto-detect the server (this container, the Docker host, then a sibling 'ollama' container); set it explicitly to override, e.g. http://<host>:11434. Optional override for OpenAI/Anthropic/OpenRouter - leave blank to use each provider's default endpoint.",
                attributes = {@Attribute(optional = true)})
        private String baseUrl;

        @Entity(type = EntityType.PROPERTY, name = "API Key", accessMod = AccessMod.RW,
                description = "Required for OpenAI/Anthropic/OpenRouter. Not used for Ollama.",
                attributes = {@Attribute(optional = true, format = "PASSWORD")})
        private String apiKey;

    }

}
