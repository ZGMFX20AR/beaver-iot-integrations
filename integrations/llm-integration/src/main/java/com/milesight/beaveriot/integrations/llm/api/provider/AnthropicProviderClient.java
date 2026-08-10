package com.milesight.beaveriot.integrations.llm.api.provider;

import com.milesight.beaveriot.base.utils.JsonUtils;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionRequest;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionResponse;
import com.milesight.beaveriot.integrations.llm.util.OkHttpUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to Anthropic's Messages API ({@code /v1/messages}). Anthropic has no public
 * "list models" endpoint we can rely on, so {@link #listModels()} is unused - the caller
 * seeds a static curated list instead (see {@code LlmApiService}).
 */
@Slf4j
public class AnthropicProviderClient implements LlmProviderClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final String baseUrl;
    private final String apiKey;

    public AnthropicProviderClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public boolean test() {
        // Anthropic has no lightweight unauthenticated health-check endpoint; a configured
        // API key is the only thing we can verify without spending a real completion call.
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<String> listModels() {
        return Collections.emptyList();
    }

    @Override
    public GenerateCompletionResponse generateCompletion(GenerateCompletionRequest request) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", request.getModel());
            body.put("max_tokens", DEFAULT_MAX_TOKENS);
            body.put("messages", List.of(Map.of("role", "user", "content", request.getPrompt())));
            if (request.getSystem() != null) {
                body.put("system", String.valueOf(request.getSystem()));
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", ANTHROPIC_VERSION);

            String responseBody = OkHttpUtil.postJson(baseUrl + "/v1/messages", headers, JsonUtils.toJSON(body));
            return GenerateCompletionResponse.builder()
                    .model(request.getModel())
                    .response(extractText(responseBody))
                    .done(true)
                    .build();
        } catch (Exception e) {
            log.error("Error occurs while generating completion", e);
            return GenerateCompletionResponse.builder().error(e.getMessage()).build();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractText(String responseBody) {
        Map<String, Object> parsed = JsonUtils.toMap(responseBody);
        List<Map<String, Object>> content = (List<Map<String, Object>>) parsed.get("content");
        return String.valueOf(content.get(0).get("text"));
    }

}
