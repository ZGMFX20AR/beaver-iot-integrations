package com.milesight.beaveriot.integrations.irivioc.controller;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ValidateConnectionResponse {
    private String deviceName;
    private int diCount;
    private int doCount;
    private int aiCount;
    private int pollJobCount;
    private int writeJobCount;
}
