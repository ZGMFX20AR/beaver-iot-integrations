package com.milesight.beaveriot.integrations.edgeaistream.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A configured EdgeAI box whose video pipelines can be relayed.
 * <p>
 * Deliberately holds no secret. The API key lives in the credentials service keyed by
 * {@link #id}, because instances of this class are serialised into an entity value, and
 * entity values are readable through the ordinary entity APIs.
 *
 * @author cytron
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamSource {
    /**
     * Opaque generated id, also the credentials access key and the path segment used to
     * request a stream. Generated rather than derived from the name so that renaming a
     * source does not break dashboards pointing at it.
     */
    private String id;

    /**
     * Display name, for humans picking a source in the UI.
     */
    private String name;

    /**
     * Host or host:port of the box, without a scheme - requests are always https, since
     * these boxes serve their API over TLS with a self-signed certificate.
     */
    private String host;
}
