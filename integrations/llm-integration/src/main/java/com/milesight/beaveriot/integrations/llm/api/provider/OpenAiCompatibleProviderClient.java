package com.milesight.beaveriot.integrations.llm.api.provider;

import com.milesight.beaveriot.base.utils.JsonUtils;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionRequest;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionResponse;
import com.milesight.beaveriot.integrations.llm.util.OkHttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to any OpenAI-compatible {@code /v1/chat/completions} endpoint - used for both
 * OpenAI itself and OpenRouter (which mirrors OpenAI's request/response shape under a
 * different base URL). Model discovery isn't attempted here; the caller seeds a static
 * curated list instead (see {@code LlmApiService}), so {@link #listModels()} is unused.
 */
@Slf4j
public class OpenAiCompatibleProviderClient implements LlmProviderClient {

    /**
     * Without an explicit cap, providers bill/reserve against the model's full output window
     * (e.g. OpenRouter reserves 16k tokens and rejects the call outright on credit-limited
     * accounts). Responses here are short dashboard summaries, so cap them - this matches
     * {@code AnthropicProviderClient}, whose API requires max_tokens anyway.
     */
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatibleProviderClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public boolean test() {
        try {
            OkHttpUtil.get(baseUrl + "/v1/models", authHeaders());
            return true;
        } catch (Exception e) {
            log.warn("[Not reachable]: " + baseUrl);
            return false;
        }
    }

    @Override
    public List<String> listModels() {
        return Collections.emptyList();
    }

    @Override
    public GenerateCompletionResponse generateCompletion(GenerateCompletionRequest request) {
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            if (request.getSystem() != null) {
                messages.add(Map.of("role", "system", "content", String.valueOf(request.getSystem())));
            }
            messages.add(Map.of("role", "user", "content", request.getPrompt()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.getModel());
            body.put("messages", messages);
            body.put("max_tokens", DEFAULT_MAX_TOKENS);

            String responseBody = OkHttpUtil.postJson(baseUrl + "/v1/chat/completions", authHeaders(), JsonUtils.toJSON(body));
            return GenerateCompletionResponse.builder()
                    .model(request.getModel())
                    .response(extractContent(responseBody))
                    .done(true)
                    .build();
        } catch (Exception e) {
            log.error("Error occurs while generating completion", e);
            return GenerateCompletionResponse.builder().error(e.getMessage()).build();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) {
        Map<String, Object> parsed = JsonUtils.toMap(responseBody);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");

        // OpenAI-compatible providers return HTTP 200 with an {"error": {...}} body for things
        // like an unknown model id. Surface that message instead of NPE-ing on a null "choices",
        // otherwise the real cause is invisible to the caller.
        if (choices == null || choices.isEmpty()) {
            Object error = parsed.get("error");
            String detail = error instanceof Map<?, ?> errorMap
                    ? String.valueOf(errorMap.get("message"))
                    : String.valueOf(error != null ? error : responseBody);
            throw new IllegalStateException("LLM provider returned no completion: " + detail);
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content"));
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (StringUtils.isNotBlank(apiKey)) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

}
