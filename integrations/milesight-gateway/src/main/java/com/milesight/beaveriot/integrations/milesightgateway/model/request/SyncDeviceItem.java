package com.milesight.beaveriot.integrations.milesightgateway.model.request;

import lombok.Data;

/**
 * SyncDeviceItem class.
 *
 * @author simon
 * @date 2025/3/13
 */
@Data
public class SyncDeviceItem {
    private String eui;

    private String modelId;

    /**
     * Offline timeout in minutes. Optional - falls back to
     * {@link com.milesight.beaveriot.integrations.milesightgateway.util.Constants#DEFAULT_DEVICE_OFFLINE_TIMEOUT}
     * when omitted or out of range.
     */
    private Long offlineTimeout;
}
