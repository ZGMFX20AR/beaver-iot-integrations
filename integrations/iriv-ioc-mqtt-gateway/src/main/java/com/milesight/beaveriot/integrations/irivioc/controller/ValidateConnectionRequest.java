package com.milesight.beaveriot.integrations.irivioc.controller;

import lombok.Data;

@Data
public class ValidateConnectionRequest {
    private String host;
    private String username;
    private String password;
}
