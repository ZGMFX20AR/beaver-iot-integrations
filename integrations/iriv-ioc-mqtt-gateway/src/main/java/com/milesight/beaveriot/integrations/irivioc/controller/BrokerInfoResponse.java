package com.milesight.beaveriot.integrations.irivioc.controller;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class BrokerInfoResponse {
    private String server;
    private Integer port;
    private String username;
    private String password;
    private String topicPrefix;
}
