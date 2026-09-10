package com.milesight.beaveriot.integrations.edgeaistream.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.context.api.CredentialsServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
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

    @Autowired
    EntityValueServiceProvider entityValueServiceProvider;

    @Autowired
    CredentialsServiceProvider credentialsServiceProvider;

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

    public StreamSource createSource(String name, String host, String apiKey) {
        validate(name, host);

        List<StreamSource> sources = new ArrayList<>(listSources());
        StreamSource source = new StreamSource(UUID.randomUUID().toString(), name.trim(), normaliseHost(host));
        sources.add(source);

        saveSources(sources);
        storeApiKey(source.getId(), apiKey);
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
