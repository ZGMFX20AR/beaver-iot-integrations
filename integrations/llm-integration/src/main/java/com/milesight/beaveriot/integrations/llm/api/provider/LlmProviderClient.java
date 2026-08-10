package com.milesight.beaveriot.integrations.llm.api.provider;

import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionRequest;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionResponse;

import java.util.List;

/**
 * A single backend the "Generate a completion" service can be routed to.
 * Every implementation translates the shared {@link GenerateCompletionRequest}/
 * {@link GenerateCompletionResponse} shape to and from its own provider's wire format,
 * so callers (workflow nodes, {@code AiInsightService}, etc.) never need to know which
 * provider is actually configured.
 */
public interface LlmProviderClient {

    /**
     * Checks whether the configured connection (base URL / API key) is reachable.
     */
    boolean test();

    /**
     * Discovers the models available on this connection. Providers without a discovery
     * endpoint (or where we intentionally use a static curated list instead) should
     * return an empty list rather than null.
     */
    List<String> listModels();

    GenerateCompletionResponse generateCompletion(GenerateCompletionRequest request);

}
