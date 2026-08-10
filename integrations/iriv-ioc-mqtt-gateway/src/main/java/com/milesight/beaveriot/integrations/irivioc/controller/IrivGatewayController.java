package com.milesight.beaveriot.integrations.irivioc.controller;

import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.context.api.CredentialsServiceProvider;
import com.milesight.beaveriot.context.api.MqttPubSubServiceProvider;
import com.milesight.beaveriot.context.integration.enums.CredentialsType;
import com.milesight.beaveriot.context.integration.model.Credentials;
import com.milesight.beaveriot.context.mqtt.model.MqttBrokerInfo;
import com.milesight.beaveriot.integrations.irivioc.Constants;
import com.milesight.beaveriot.integrations.irivioc.service.IrivDeviceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only diagnostic endpoint mirroring {@code mqtt-device}'s {@code /broker-info}. Not required for
 * day-to-day use (the {@code push_mqtt_settings} device action configures the gateway automatically), but
 * useful for manually double-checking or re-entering the broker connection details on the gateway itself.
 */
@RestController
@RequestMapping("/" + Constants.INTEGRATION_ID)
public class IrivGatewayController {
    private final MqttPubSubServiceProvider mqttPubSubServiceProvider;
    private final CredentialsServiceProvider credentialsServiceProvider;
    private final IrivDeviceService irivDeviceService;

    public IrivGatewayController(MqttPubSubServiceProvider mqttPubSubServiceProvider, CredentialsServiceProvider credentialsServiceProvider, IrivDeviceService irivDeviceService) {
        this.mqttPubSubServiceProvider = mqttPubSubServiceProvider;
        this.credentialsServiceProvider = credentialsServiceProvider;
        this.irivDeviceService = irivDeviceService;
    }

    /**
     * Non-mutating pre-flight check for the Add Gateway wizard - logs in and reads config only, so the
     * user can see what will be found (and get a clear error if the credentials are wrong) before
     * committing to actually adding the device.
     */
    @PostMapping("/validate-connection")
    public ResponseBody<ValidateConnectionResponse> validateConnection(@RequestBody ValidateConnectionRequest request) {
        IrivDeviceService.ConnectionPreview preview = irivDeviceService.previewConnection(request.getHost(), request.getUsername(), request.getPassword());
        return ResponseBuilder.success(ValidateConnectionResponse.builder()
                .deviceName(preview.deviceName())
                .diCount(preview.diCount())
                .doCount(preview.doCount())
                .aiCount(preview.aiCount())
                .pollJobCount(preview.pollJobCount())
                .writeJobCount(preview.writeJobCount())
                .build());
    }

    @GetMapping("/broker-info")
    public ResponseBody<BrokerInfoResponse> getBrokerInfo() {
        MqttBrokerInfo mqttBrokerInfo = mqttPubSubServiceProvider.getMqttBrokerInfo();
        if (mqttBrokerInfo == null || mqttBrokerInfo.getMqttPort() == null) {
            throw ServiceException.with(ErrorCode.SERVER_ERROR.getErrorCode(), "Mqtt broker not found").build();
        }
        Credentials mqttCredentials = credentialsServiceProvider.getOrCreateCredentials(CredentialsType.MQTT);
        if (StringUtils.isEmpty(mqttCredentials.getAccessKey())) {
            throw ServiceException.with(ErrorCode.SERVER_ERROR.getErrorCode(), "Mqtt broker username empty").build();
        }
        String topicPrefix = mqttPubSubServiceProvider.getFullTopicName(mqttCredentials.getAccessKey(), "") + Constants.INTEGRATION_ID;
        return ResponseBuilder.success(BrokerInfoResponse.builder()
                .server(mqttBrokerInfo.getHost())
                .port(mqttBrokerInfo.getMqttPort())
                .username(mqttCredentials.getAccessKey())
                .password(mqttCredentials.getAccessSecret())
                .topicPrefix(topicPrefix)
                .build());
    }
}
