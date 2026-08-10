package com.milesight.beaveriot.integrations.irivioc;

public class Constants {
    private Constants() {
    }

    public static final String INTEGRATION_ID = "iriv-ioc-mqtt-gateway";

    // keys used in Device#getAdditional()
    public static final String ADDITIONAL_HOST = "host";
    public static final String ADDITIONAL_USERNAME = "username";
    public static final String ADDITIONAL_PASSWORD = "password";
    public static final String ADDITIONAL_BASE_TOPIC = "baseTopic";
    public static final String ADDITIONAL_CHANNEL_TOPICS = "channelTopics";

    // prefix of the MQTT clientId pushed to the physical gateway (see IrivDeviceService#pushSettings) -
    // also used to map an incoming MqttConnectEvent/MqttDisconnectEvent's clientId back to our device
    public static final String MQTT_CLIENT_ID_PREFIX = "beaveriot-";

    // fallback offline timeout, only used if a disconnect event is ever missed (e.g. an abrupt network
    // drop the broker itself takes a while to notice) - real-time connect/disconnect events are the
    // primary signal, see IrivMqttService#onGatewayConnect/onGatewayDisconnect
    public static final long DEFAULT_OFFLINE_TIMEOUT_SECONDS = 120L;
}
