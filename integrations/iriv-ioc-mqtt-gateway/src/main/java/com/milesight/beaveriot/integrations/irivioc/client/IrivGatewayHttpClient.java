package com.milesight.beaveriot.integrations.irivioc.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.integrations.irivioc.client.model.IrivConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Talks to an IRIV-IOC MQTT Gateway's own admin REST API (its local web UI backend, not MQTT)
 * to log in and read/write its configuration - used only for device discovery and one-click
 * "push our MQTT broker settings to the gateway", never for the runtime data path.
 */
@Slf4j
@Component
public class IrivGatewayHttpClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Session(String host, String cookie, String csrfToken) {
    }

    public Session login(String host, String username, String password) {
        String requestBody = toJson(Map.of("user", username, "pass", password));
        HttpResponse<String> response = send(baseRequest(host, "/api/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)));

        if (response.statusCode() != 200) {
            throw gatewayError("Login to gateway " + host + " failed (HTTP " + response.statusCode() + ")");
        }

        String setCookie = response.headers().firstValue("Set-Cookie")
                .orElseThrow(() -> gatewayError("Gateway " + host + " did not return a session cookie on login"));
        String csrfToken = parseConfigTree(response.body()).path("csrfToken").asText("");
        return new Session(host, setCookie.split(";", 2)[0], csrfToken);
    }

    /**
     * Raw JSON text of {@code GET /api/config} - kept as text (rather than parsed once) so callers can
     * both read it as {@link IrivConfig} (for entity building) and mutate it as a {@link JsonNode} tree
     * (to build a lossless config patch, see {@link #parseConfigTree}) without a second HTTP round-trip.
     */
    public String getConfigJson(Session session) {
        HttpResponse<String> response = send(baseRequest(session.host(), "/api/config")
                .header("Cookie", session.cookie())
                .GET());

        if (response.statusCode() != 200) {
            throw gatewayError("Fetching config from gateway " + session.host() + " failed (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    public IrivConfig parseConfig(String configJson) {
        try {
            return JSON.readValue(configJson, IrivConfig.class);
        } catch (IOException e) {
            throw gatewayError("Gateway returned an unreadable config: " + e.getMessage());
        }
    }

    public JsonNode parseConfigTree(String configJson) {
        try {
            return JSON.readTree(configJson);
        } catch (IOException e) {
            throw gatewayError("Gateway returned an unreadable config: " + e.getMessage());
        }
    }

    /**
     * Pushes a config patch back to the gateway the same way its own web UI does per-page saves. Callers
     * should mutate a full section fetched via {@link #parseConfigTree} (rather than building a fresh
     * object) so unrelated fields the gateway firmware doesn't echo back as defaults are preserved.
     */
    public void postConfig(Session session, Object patch) {
        HttpRequest.Builder request = baseRequest(session.host(), "/api/config")
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookie())
                .POST(HttpRequest.BodyPublishers.ofString(toJson(patch)));
        if (session.csrfToken() != null && !session.csrfToken().isEmpty()) {
            request.header("X-CSRF-Token", session.csrfToken());
        }
        HttpResponse<String> response = send(request);

        if (response.statusCode() != 200) {
            throw gatewayError("Pushing config to gateway " + session.host() + " failed (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    private HttpRequest.Builder baseRequest(String host, String path) {
        return HttpRequest.newBuilder(URI.create("http://" + host + path)).timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
        try {
            return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw gatewayError("Cannot reach gateway: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw gatewayError("Failed to encode request body: " + e.getMessage());
        }
    }

    private ServiceException gatewayError(String message) {
        log.warn(message);
        return ServiceException.with(ErrorCode.SERVER_ERROR.getErrorCode(), message).build();
    }
}
