package com.milesight.beaveriot.integrations.milesightgateway.model.request;

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
     */
    private Integer fPort;
}
