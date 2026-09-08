package com.milesight.beaveriot.integrations.milesightgateway.model.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a codec preview run. Always returned with HTTP 200 - decode failures are
 * reported via {@link #errorMessage}, not as an HTTP-level error, since a broken
 * codec while editing is an expected, informative outcome, not a system error.
 *
 * @author cytron
 */
@Data
@Builder
public class TestCodecResponse {
    private boolean success;

    /**
     * Decoded JSON, present only when {@link #success} is true.
     */
    private JsonNode output;

    /**
     * Readable failure reason, present only when {@link #success} is false.
     */
    private String errorMessage;

    public static TestCodecResponse ok(JsonNode output) {
        return TestCodecResponse.builder().success(true).output(output).build();
    }

    public static TestCodecResponse failed(String errorMessage) {
        return TestCodecResponse.builder().success(false).errorMessage(errorMessage).build();
    }
}
