package com.milesight.beaveriot.integrations.edgeaistream.model.response;

import com.milesight.beaveriot.integrations.edgeaistream.model.StreamSource;
import lombok.Data;

/**
 * A camera source as shown in the UI.
 * <p>
 * Carries no API key, and deliberately has no field to put one in: a key is write-only,
 * so there is no path by which this response could start leaking one. {@link #hasApiKey}
 * exists so the UI can say whether a key is set without revealing it.
 *
 * @author cytron
 */
@Data
public class StreamSourceResponse {
    private String id;

    private String name;

    private String host;

    private boolean hasApiKey;

    /**
     * Relative path a dashboard image widget should point at, with the pipeline id left
     * for the user to fill in. Returned so nobody has to reconstruct the URL by hand.
     */
    private String streamPathTemplate;

    public static StreamSourceResponse of(StreamSource source, boolean hasApiKey, String integrationId) {
        StreamSourceResponse response = new StreamSourceResponse();
        response.setId(source.getId());
        response.setName(source.getName());
        response.setHost(source.getHost());
        response.setHasApiKey(hasApiKey);
        response.setStreamPathTemplate("/api/v1/" + integrationId + "/streams/" + source.getId() + "/{pipelineId}");
        return response;
    }
}
