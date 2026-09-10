package com.milesight.beaveriot.integrations.edgeaistream.model.request;

import lombok.Data;

/**
 * Create or update payload for a camera source.
 *
 * @author cytron
 */
@Data
public class StreamSourceRequest {
    /**
     * Display name, shown when picking a source.
     */
    private String name;

    /**
     * Host or host:port of the box. A pasted scheme or trailing slash is tolerated and
     * stripped on save.
     */
    private String host;

    /**
     * The box's X-API-Key. On update, leave blank to keep the existing key - the UI never
     * reads a key back, so it has nothing to resend when only the name or host changed.
     */
    private String apiKey;
}
