package com.milesight.beaveriot.integrations.edgeaistream.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.context.api.CredentialsServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.security.TenantContext;
import com.milesight.beaveriot.context.integration.model.Credentials;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.context.integration.wrapper.AnnotatedEntityWrapper;
import com.milesight.beaveriot.integrations.edgeaistream.entity.EdgeAiStreamEntities;
import com.milesight.beaveriot.integrations.edgeaistream.model.StreamSource;
import com.milesight.beaveriot.integrations.edgeaistream.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the configured camera sources, and their API keys.
 * <p>
 * Split across two stores on purpose: the source records go into a hidden PROPERTY entity
 * as JSON (the same approach milesight-gateway uses for its gateway/device map), while
 * each API key goes into the credentials service. Keeping keys out of the entity value is
 * the point of the split - entity values are retrievable through the entity APIs.
 *
 * @author cytron
 */
@Component
@Slf4j
public class StreamSourceService {
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Sources and their keys, by tenant, for the relay to read without touching the
     * database.
     * <p>
     * This is not an optimisation, it is what makes concurrent viewing possible at all.
     * {@code open-in-view} binds an EntityManager to the request, so a query on a stream
     * request holds its JDBC connection until that request finishes - and a stream request
     * finishes when the viewer leaves, possibly hours later. Measured: one viewer held one
     * connection for the life of the stream, so with Hikari's default pool of ten, the
     * eleventh viewer starved the entire application, not merely the streams. Ordinary API
     * calls went from 17ms to HTTP 500 after the 5s connection timeout.
     * <p>
     * Kept fresh by reloading after every change, so the relay reads no database at all.
     */
    private final Map<String, Map<String, CachedSource>> cacheByTenant = new ConcurrentHashMap<>();

    @Autowired
    EntityValueServiceProvider entityValueServiceProvider;

    @Autowired
    CredentialsServiceProvider credentialsServiceProvider;

    /**
     * A source plus its key, as the relay needs it.
     */
    public record CachedSource(StreamSource source, String apiKey) {
    }

    public List<StreamSource> listSources() {
        AnnotatedEntityWrapper<EdgeAiStreamEntities> wrapper = new AnnotatedEntityWrapper<>();
        String raw = (String) wrapper.getValue(EdgeAiStreamEntities::getSources).orElse("[]");
        try {
            return json.readValue(raw, new TypeReference<List<StreamSource>>() {
            });
        } catch (Exception e) {
            throw ServiceException.with(ErrorCode.SERVER_ERROR.getErrorCode(),
                    "Stored camera source list is unreadable: " + e.getMessage()).build();
        }
    }

    public StreamSource getSource(String id) {
        return listSources().stream()
                .filter(source -> source.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ServiceException.with(ErrorCode.DATA_NO_FOUND.getErrorCode(),
                        "No camera source with id: " + id).build());
    }

    /**
     * The API key for a source, as stored alongside it.
     *
     * @return empty when the source exists but has no key recorded, which is a usable
     * state only if the box happens not to require one
     */
    public Optional<String> getApiKey(String sourceId) {
        return credentialsServiceProvider.getCredentials(Constants.CREDENTIAL_TYPE, sourceId)
                .map(Credentials::getAccessSecret)
                .filter(key -> !StringUtils.isEmpty(key));
    }

    /**
     * Look a source up for the relay, without querying the database - see the note on
     * {@link #cacheByTenant} for why that matters.
     *
     * @return empty if no such source is known to this tenant
     */
    public Optional<CachedSource> lookupForRelay(String tenantId, String sourceId) {
        return Optional.ofNullable(cacheByTenant.get(tenantId))
                .map(sources -> sources.get(sourceId));
    }

    /**
     * Re-read this tenant's sources into the relay cache.
     * <p>
     * Must be called from a thread that can safely hold a database connection for the
     * duration of the read - i.e. a normal request or startup, never a stream request.
     */
    public void refreshCache(String tenantId) {
        Map<String, CachedSource> refreshed = new ConcurrentHashMap<>();
        for (StreamSource source : listSources()) {
            refreshed.put(source.getId(), new CachedSource(source, getApiKey(source.getId()).orElse(null)));
        }
        cacheByTenant.put(tenantId, refreshed);
        log.debug("Cached {} camera source(s) for tenant '{}'", refreshed.size(), tenantId);
    }

    public StreamSource createSource(String name, String host, String apiKey) {
        validate(name, host);

        List<StreamSource> sources = new ArrayList<>(listSources());
        StreamSource source = new StreamSource(UUID.randomUUID().toString(), name.trim(), normaliseHost(host));
        sources.add(source);

        saveSources(sources);
        storeApiKey(source.getId(), apiKey);
        refreshCacheForCurrentTenant();
        return source;
    }

    public StreamSource updateSource(String id, String name, String host, String apiKey) {
        validate(name, host);

        List<StreamSource> sources = new ArrayList<>(listSources());
        StreamSource source = sources.stream()
                .filter(candidate -> candidate.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ServiceException.with(ErrorCode.DATA_NO_FOUND.getErrorCode(),
                        "No camera source with id: " + id).build());

        source.setName(name.trim());
        source.setHost(normaliseHost(host));
        saveSources(sources);

        // A blank key means "leave it alone", so the UI can edit a source's name or host
        // without having to re-enter a secret it never displays back to the user.
        if (!StringUtils.isEmpty(apiKey)) {
            storeApiKey(id, apiKey);
        }
        refreshCacheForCurrentTenant();
        return source;
    }

    public void deleteSource(String id) {
        List<StreamSource> sources = new ArrayList<>(listSources());
        if (!sources.removeIf(source -> source.getId().equals(id))) {
            throw ServiceException.with(ErrorCode.DATA_NO_FOUND.getErrorCode(),
                    "No camera source with id: " + id).build();
        }
        saveSources(sources);
        deleteApiKey(id);
        refreshCacheForCurrentTenant();
    }

    /**
     * Refresh the relay cache for whoever is making this change. Safe here because these
     * are ordinary authenticated requests, not streams.
     */
    private void refreshCacheForCurrentTenant() {
        TenantContext.tryGetTenantId().ifPresent(this::refreshCache);
    }

    /**
     * Write the key for a source, replacing any key already held for it.
     * <p>
     * The delete is not redundant: {@code getOrCreateCredentials} returns an existing
     * credential untouched rather than updating its secret, so calling it alone would
     * silently keep the old key - and rotating keys is the whole reason this feature
     * exists. Delete first, then create.
     */
    private void storeApiKey(String sourceId, String apiKey) {
        if (StringUtils.isEmpty(apiKey)) {
            return;
        }
        deleteApiKey(sourceId);
        credentialsServiceProvider.getOrCreateCredentials(Constants.CREDENTIAL_TYPE, sourceId, apiKey);
    }

    private void deleteApiKey(String sourceId) {
        credentialsServiceProvider.getCredentials(Constants.CREDENTIAL_TYPE, sourceId)
                .map(Credentials::getId)
                .ifPresent(credentialsId ->
                        credentialsServiceProvider.batchDeleteCredentials(List.of(credentialsId)));
    }

    private void saveSources(List<StreamSource> sources) {
        try {
            entityValueServiceProvider.saveLatestValues(ExchangePayload.create(Map.of(
                    EdgeAiStreamEntities.SOURCES_KEY, json.writeValueAsString(sources)
            )));
        } catch (Exception e) {
            throw ServiceException.with(ErrorCode.SERVER_ERROR.getErrorCode(),
                    "Could not save camera sources: " + e.getMessage()).build();
        }
    }

    private void validate(String name, String host) {
        if (StringUtils.isEmpty(name)) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Camera source name is required").build();
        }
        if (StringUtils.isEmpty(host)) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Camera source host is required").build();
        }
    }

    /**
     * Strip a scheme or trailing slash if one was pasted in, so the stored value is always
     * just host[:port] and the relay can build its URL without guessing.
     */
    private String normaliseHost(String host) {
        return host.trim()
                .replaceAll("^[a-zA-Z]+://", "")
                .replaceAll("/+$", "");
    }
}
