package com.milesight.beaveriot.integrations.edgeaistream.util;

/**
 * @author cytron
 */
public class Constants {
    private Constants() {
    }

    /**
     * Must match the key under {@code integration:} in integration.yaml, and is the prefix
     * every controller in this module mounts under.
     */
    public static final String INTEGRATION_ID = "edgeai-stream";

    /**
     * Credential type used to store each source's API key. Deliberately not the shared
     * {@code HTTP} type: that one is looked up by a single default access key per tenant
     * and is already claimed by the workflow HTTP-In feature, so reusing it would collide.
     */
    public static final String CREDENTIAL_TYPE = "EDGEAI_STREAM";
}
