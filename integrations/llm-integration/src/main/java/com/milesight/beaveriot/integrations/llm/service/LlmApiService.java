package com.milesight.beaveriot.integrations.llm.service;

import com.milesight.beaveriot.base.utils.JsonUtils;
import com.milesight.beaveriot.context.api.EntityServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.AttributeBuilder;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.context.integration.wrapper.AnnotatedEntityWrapper;
import com.milesight.beaveriot.eventbus.annotations.EventSubscribe;
import com.milesight.beaveriot.eventbus.api.Event;
import com.milesight.beaveriot.eventbus.api.EventResponse;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionRequest;
import com.milesight.beaveriot.integrations.llm.api.model.GenerateCompletionResponse;
import com.milesight.beaveriot.integrations.llm.api.provider.AnthropicProviderClient;
import com.milesight.beaveriot.integrations.llm.api.provider.LlmProviderClient;
import com.milesight.beaveriot.integrations.llm.api.provider.OllamaProviderClient;
import com.milesight.beaveriot.integrations.llm.api.provider.OpenAiCompatibleProviderClient;
import com.milesight.beaveriot.integrations.llm.constant.LlmIntegrationConstants;
import com.milesight.beaveriot.integrations.llm.entity.LlmConnectionPropertiesEntities;
import com.milesight.beaveriot.integrations.llm.entity.LlmServiceEntities;
import com.milesight.beaveriot.integrations.llm.enums.LlmProvider;
import com.milesight.beaveriot.integrations.llm.process.LocalOllamaProcessManager;
import com.milesight.beaveriot.integrations.llm.util.OkHttpUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class LlmApiService {

    @Autowired
    private EntityValueServiceProvider entityValueServiceProvider;
    @Autowired
    private EntityServiceProvider entityServiceProvider;
    @Autowired
    private LocalOllamaProcessManager localOllamaProcessManager;

    private LlmProviderClient client;
    private LlmConnectionPropertiesEntities.LlmProperties currentProperties;

    private static final String MODEL_ATTRIBUTE_NAME = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.generate_completion.model";

    /**
     * Static, curated model lists for providers that don't have a discovery endpoint we
     * rely on. Kept short and current as of when this integration was last updated -
     * edit this map directly to add new models rather than trying to fetch them live.
     */
    private static final Map<LlmProvider, List<String>> CURATED_MODELS = Map.of(
            LlmProvider.OPENAI, List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o3", "o3-mini"),
            LlmProvider.ANTHROPIC, List.of("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5-20251001"),
            LlmProvider.OPENROUTER, List.of("openai/gpt-4o", "anthropic/claude-sonnet-5", "meta-llama/llama-3.1-70b-instruct", "google/gemini-2.0-flash-001")
    );

    private static final Map<LlmProvider, String> DEFAULT_BASE_URLS = Map.of(
            LlmProvider.OPENAI, "https://api.openai.com",
            LlmProvider.ANTHROPIC, "https://api.anthropic.com",
            LlmProvider.OPENROUTER, "https://openrouter.ai/api"
    );

    /**
     * Where an Ollama server is looked for when no Base URL is configured, in priority order:
     * bundled in this container (the hailo-ollama NPU build), then on the Docker host, then a
     * sibling container named "ollama".
     */
    private static final List<String> OLLAMA_BASE_URL_CANDIDATES = List.of(
            "http://127.0.0.1:11434",
            "http://host.docker.internal:11434",
            "http://ollama:11434"
    );

    private static final String OLLAMA_TAGS_PATH = "/api/tags";

    /** Kept short: this runs during integration bootstrap, once per candidate. */
    private static final int OLLAMA_PROBE_TIMEOUT_SECONDS = 2;

    public void init() {
        try {
            LlmConnectionPropertiesEntities.LlmProperties llmProperties = entityValueServiceProvider.findValuesByKey(
                    LlmConnectionPropertiesEntities.getKey(LlmConnectionPropertiesEntities.Fields.llmProperties), LlmConnectionPropertiesEntities.LlmProperties.class);
            if (!llmProperties.isEmpty()) {
                initConnection(llmProperties);
                initModels();
            }
        } catch (Exception e) {
            log.error("Error occurs while initializing connection", e);
            AnnotatedEntityWrapper<LlmConnectionPropertiesEntities> wrapper = new AnnotatedEntityWrapper<>();
            wrapper.saveValues(
                    Map.of(LlmConnectionPropertiesEntities::getApiStatus, Boolean.FALSE, LlmConnectionPropertiesEntities::getModels, "")
            ).publishSync();
            saveModelAttributes(new LinkedHashMap<>());
        }
    }

    @EventSubscribe(payloadKeyExpression = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.llm_properties.*")
    public void onLlmPropertiesUpdate(Event<LlmConnectionPropertiesEntities.LlmProperties> event) {
        if (isConfigChanged(event)) {
            LlmConnectionPropertiesEntities.LlmProperties llmProperties = event.getPayload();
            initConnection(llmProperties);
            initModels();
        }
    }

    private boolean isConfigChanged(Event<LlmConnectionPropertiesEntities.LlmProperties> event) {
        LlmConnectionPropertiesEntities.LlmProperties llmProperties = event.getPayload();
        if (StringUtils.isBlank(llmProperties.getProviderType())) {
            return false;
        }
        if (client == null || currentProperties == null) {
            return true;
        }
        return !Objects.equals(currentProperties.getProviderType(), llmProperties.getProviderType())
                || !Objects.equals(currentProperties.getBaseUrl(), llmProperties.getBaseUrl())
                || !Objects.equals(currentProperties.getApiKey(), llmProperties.getApiKey());
    }

    @SneakyThrows
    @EventSubscribe(payloadKeyExpression = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.test_connection")
    public void testConnection(Event<LlmServiceEntities> event) {
        initModels();
    }

    @SneakyThrows
    @EventSubscribe(payloadKeyExpression = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.generate_completion.*")
    public EventResponse onGenerateCompletion(Event<LlmServiceEntities.GenerateCompletion> event) {
        LlmServiceEntities.GenerateCompletion payload = event.getPayload();
        GenerateCompletionRequest request = new GenerateCompletionRequest().converterPayload(payload);
        GenerateCompletionResponse generateCompletionResponse = client.generateCompletion(request);
        return getEventResponse(generateCompletionResponse);
    }

    @EventSubscribe(payloadKeyExpression = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.start_local_server")
    public void onStartLocalServer(Event<LlmServiceEntities.StartLocalServer> event) {
        try {
            localOllamaProcessManager.start();
        } finally {
            publishLocalServerRunning();
        }
    }

    @EventSubscribe(payloadKeyExpression = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.stop_local_server")
    public void onStopLocalServer(Event<LlmServiceEntities.StopLocalServer> event) {
        try {
            localOllamaProcessManager.stop();
        } finally {
            publishLocalServerRunning();
        }
    }

    private void publishLocalServerRunning() {
        AnnotatedEntityWrapper<LlmConnectionPropertiesEntities> wrapper = new AnnotatedEntityWrapper<>();
        wrapper.saveValue(LlmConnectionPropertiesEntities::getLocalServerRunning, localOllamaProcessManager.isRunning()).publishSync();
    }

    private static EventResponse getEventResponse(GenerateCompletionResponse generateCompletionResponse) {
        Map<String, Object> response = JsonUtils.toMap(generateCompletionResponse);
        EventResponse eventResponse = EventResponse.empty();
        for (Map.Entry<String, Object> entry : response.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            eventResponse.put(key, value);
        }
        return eventResponse;
    }

    private void initConnection(LlmConnectionPropertiesEntities.LlmProperties llmProperties) {
        currentProperties = llmProperties;
        LlmProvider provider = resolveProvider(llmProperties.getProviderType());

        String baseUrl;
        if (provider == LlmProvider.OLLAMA) {
            baseUrl = resolveOllamaBaseUrl(llmProperties.getBaseUrl());
            // Write back what we actually call, so a blank or scheme-less value the user
            // typed shows up corrected on the settings page instead of silently failing.
            persistBaseUrl(llmProperties, baseUrl);
        } else {
            baseUrl = StringUtils.isNotBlank(llmProperties.getBaseUrl())
                    ? normalizeBaseUrl(llmProperties.getBaseUrl())
                    : DEFAULT_BASE_URLS.get(provider);
        }

        client = switch (provider) {
            case OPENAI, OPENROUTER -> new OpenAiCompatibleProviderClient(baseUrl, llmProperties.getApiKey());
            case ANTHROPIC -> new AnthropicProviderClient(baseUrl, llmProperties.getApiKey());
            case OLLAMA -> new OllamaProviderClient(baseUrl);
        };
    }

    /**
     * Adds a missing scheme and strips trailing slashes.
     *
     * <p>OkHttp rejects a URL with no scheme outright - {@code localhost:11434} raises
     * "Expected URL scheme 'http' or 'https' but was 'localhost'", which surfaces only as a
     * failed connection test and an empty model list, giving the user no hint what is wrong.
     *
     * @return the normalized URL, or null when the input is blank
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return null;
        }
        String url = baseUrl.trim();
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            url = "http://" + url;
        }
        return url.replaceAll("/+$", "");
    }

    /**
     * Resolves the Ollama endpoint, probing the usual locations when none is configured.
     *
     * <p>Ollama has a fixed default port and no authentication, so the endpoint is discoverable:
     * rather than making the user work out whether the server is in this container, on the
     * Docker host, or a sibling service, try each in turn and keep the first that answers
     * {@code /api/tags}. An explicitly configured URL always wins - this only fills in a blank.
     */
    private String resolveOllamaBaseUrl(String configuredBaseUrl) {
        String normalized = normalizeBaseUrl(configuredBaseUrl);
        if (normalized != null) {
            return normalized;
        }

        for (String candidate : OLLAMA_BASE_URL_CANDIDATES) {
            if (isOllamaReachable(candidate)) {
                log.info("Auto-detected Ollama server at {}", candidate);
                return candidate;
            }
        }

        String fallback = OLLAMA_BASE_URL_CANDIDATES.get(0);
        log.warn("No Ollama server answered on any of {}; falling back to {}. Set the Base URL on the"
                + " LLM Integration page if the server runs somewhere else.", OLLAMA_BASE_URL_CANDIDATES, fallback);
        return fallback;
    }

    private boolean isOllamaReachable(String baseUrl) {
        try {
            OkHttpUtil.get(baseUrl + OLLAMA_TAGS_PATH, null, OLLAMA_PROBE_TIMEOUT_SECONDS);
            return true;
        } catch (Exception e) {
            log.debug("No Ollama server at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    private void persistBaseUrl(LlmConnectionPropertiesEntities.LlmProperties llmProperties, String baseUrl) {
        if (Objects.equals(llmProperties.getBaseUrl(), baseUrl)) {
            return;
        }
        // Update the in-memory copy before saving: the save re-enters onLlmPropertiesUpdate,
        // and isConfigChanged() has to see the new value or this recurses.
        llmProperties.setBaseUrl(baseUrl);
        try {
            new AnnotatedEntityWrapper<LlmConnectionPropertiesEntities.LlmProperties>()
                    .saveValue(LlmConnectionPropertiesEntities.LlmProperties::getBaseUrl, baseUrl)
                    .publishSync();
        } catch (Exception e) {
            // Not fatal - the resolved URL is already in use for this session
            log.warn("Could not persist the resolved Ollama base URL '{}'", baseUrl, e);
        }
    }

    private LlmProvider resolveProvider(String providerType) {
        if (StringUtils.isBlank(providerType)) {
            return LlmProvider.OLLAMA;
        }
        // Match by EnumCode (case-insensitive) rather than valueOf(): the persisted value is
        // the enum's code, and we also want to tolerate legacy uppercase values still in the DB.
        for (LlmProvider provider : LlmProvider.values()) {
            if (provider.getCode().equalsIgnoreCase(providerType) || provider.name().equalsIgnoreCase(providerType)) {
                return provider;
            }
        }
        log.warn("Unknown LLM provider '{}', falling back to Ollama", providerType);
        return LlmProvider.OLLAMA;
    }

    private void initModels() {
        String modelsAsString = "";
        Map<String, String> modelsEnum = new LinkedHashMap<>();
        try {
            if (testConnection()) {
                LlmProvider provider = currentProperties == null
                        ? LlmProvider.OLLAMA
                        : resolveProvider(currentProperties.getProviderType());
                List<String> modelsAsList = provider == LlmProvider.OLLAMA
                        ? client.listModels()
                        : CURATED_MODELS.getOrDefault(provider, List.of());
                if (modelsAsList != null && !modelsAsList.isEmpty()) {
                    modelsAsString = StringUtils.join(modelsAsList, ",");
                    modelsAsList.forEach(model -> modelsEnum.put(model, model));
                }
            }
        } catch (Exception e) {
            log.error("Error occurs while getting models", e);
        }
        AnnotatedEntityWrapper<LlmConnectionPropertiesEntities> wrapper = new AnnotatedEntityWrapper<>();
        wrapper.saveValue(LlmConnectionPropertiesEntities::getModels, modelsAsString).publishSync();
        saveModelAttributes(modelsEnum);
    }

    private boolean testConnection() {
        boolean isConnection = Boolean.FALSE;
        try {
            if (client != null) {
                isConnection = client.test();
            }
        } catch (Exception e) {
            log.error("Error occurs while testing connection", e);
        }
        AnnotatedEntityWrapper<LlmConnectionPropertiesEntities> wrapper = new AnnotatedEntityWrapper<>();
        wrapper.saveValue(LlmConnectionPropertiesEntities::getApiStatus, isConnection).publishSync();
        return isConnection;
    }

    private void saveModelAttributes(Map<String, String> modelsEnum) {
        Entity entity = entityServiceProvider.findByKey(MODEL_ATTRIBUTE_NAME);
        Map<String, Object> attributes = entity.getAttributes();
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        }
        attributes.put(AttributeBuilder.ATTRIBUTE_ENUM, modelsEnum);
        entity.setAttributes(attributes);
        entityServiceProvider.save(entity);
    }

}
