package com.milesight.beaveriot.integrations.milesightgateway.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Sample input for previewing an in-progress (possibly unsaved) codec before it is
 * attached to a device model - lets a user confirm a decoder actually works before
 * saving the model or adding a real device.
 *
 * @author cytron
 */
@Data
public class TestCodecRequest {
    /**
     * JS source under test - the editor's current, possibly unsaved value, not
     * necessarily what's persisted for the model.
     */
    private String codecCode;

    /**
     * Decoder entry function name within {@link #codecCode}.
     */
    private String codecEntry;

    /**
     * Hex-encoded sample uplink payload, e.g. "01AABBCC". An optional "0x" prefix and
     * whitespace between bytes are tolerated.
     */
    private String payloadHex;

    /**
     * LoRaWAN fPort of the sample uplink. Defaults to 0 when omitted.
     *
     * Explicit @JsonProperty because the global snake_case naming strategy derives
     * JSON property names from getter methods, not raw field names: stripping "get"
     * from Lombok's generated getFPort() leaves "FPort", whose first two characters
     * are both uppercase - the standard java.beans.Introspector decapitalize rule
     * (which Jackson replicates) leaves names like that entirely uncapitalized-first
     * unchanged rather than lowercasing just the first letter, so the strategy then
     * snake-cases "FPort" itself into "fport" (no underscore), not "f_port". Verified
     * empirically: the request silently bound fPort to 0 when sent as "f_port" (this
     * app's actual convention, used by every sibling field here without issue) and
     * only worked when sent as "fport". MqttUplinkData's own fPort field hits the
     * exact same issue and is already guarded with @JsonAlias("fPort") for the same
     * reason - this DTO just didn't get the same treatment when it was added.
     */
    @JsonProperty("f_port")
    private Integer fPort;
}
