package com.milesight.beaveriot.integrations.llm.api.provider;

import com.milesight.beaveriot.integrations.llm.api.OllamaClient;
import com.milesight.beaveriot.integrations.llm.api.config.Config;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionRequest;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionResponse;
import com.milesight.beaveriot.integrations.llm.api.model.TagsResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Thin adapter over the existing {@link OllamaClient} - behavior is unchanged from
 * before this integration supported multiple providers (dynamic model discovery via
 * {@code /api/tags}, completions via {@code /api/generate}).
 */
@Slf4j
public class OllamaProviderClient implements LlmProviderClient {

    private final OllamaClient client;

    public OllamaProviderClient(String baseUrl) {
        this.client = OllamaClient.builder()
                .config(Config.builder().baseUrl(baseUrl).build())
                .build();
    }

    @Override
    public boolean test() {
        return client.test();
    }

    @Override
    public List<String> listModels() {
        try {
            TagsResponse tags = client.getTags();
            List<String> models = tags.getModelsAsList();
            return models == null ? Collections.emptyList() : models;
        } catch (Exception e) {
            log.error("Error occurs while getting Ollama models", e);
            return Collections.emptyList();
        }
    }

    @Override
    public GenerateCompletionResponse generateCompletion(GenerateCompletionRequest request) {
        return client.postGenerateCompletion(request);
    }

}
