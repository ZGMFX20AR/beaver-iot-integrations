package com.milesight.beaveriot.integrations.irivioc.service;

import com.milesight.beaveriot.context.api.DeviceServiceProvider;
import com.milesight.beaveriot.context.api.DeviceStatusServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.api.MqttPubSubServiceProvider;
import com.milesight.beaveriot.context.constants.IntegrationConstants;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.context.mqtt.model.MqttConnectEvent;
import com.milesight.beaveriot.context.mqtt.model.MqttDisconnectEvent;
import com.milesight.beaveriot.integrations.irivioc.Constants;
import com.milesight.beaveriot.integrations.irivioc.util.TopicSupport;
import com.milesight.beaveriot.integrations.irivioc.util.ValueCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Subscribes to every IRIV-IOC gateway device's uplink topics (DI state, DO state, AI, Modbus poll jobs)
 * and turns them into entity value updates. Downlink writes (DO command, Modbus write job) are published
 * directly from {@link IrivDeviceService#onDeviceEntityWrite} instead, since those are triggered by entity
 * writes rather than incoming MQTT traffic.
 */
@Slf4j
@Service
public class IrivMqttService {
    private static final Set<String> BOOLEAN_CHANNELS = Set.of(TopicSupport.CHANNEL_DI, TopicSupport.CHANNEL_DO_STATE);

    // Any non-empty eventType saves the value the same way but is dispatched under a DIFFERENT
    // ExchangeEvent.EventType than the platform's default for PROPERTY entities (UPDATE_PROPERTY) - so this
    // uplink-triggered save doesn't get picked back up by our own @EventSubscribe(eventType = {..,
    // UPDATE_PROPERTY}) downlink write-handler in IrivDeviceService, which would otherwise re-publish the
    // same command back to the gateway every time it reports its state, causing a rapid feedback loop
    // (symptom: a DO-driven relay/light flickering instead of switching once). Same trick used by
    // milesight-gateway ("DEVICE_UPLINK") and msc-integration ("LATEST_VALUE").
    private static final String EVENT_TYPE_DEVICE_REPORT = "DEVICE_REPORT";

    private final MqttPubSubServiceProvider mqttPubSubServiceProvider;
    private final DeviceServiceProvider deviceServiceProvider;
    private final EntityValueServiceProvider entityValueServiceProvider;
    private final DeviceStatusServiceProvider deviceStatusServiceProvider;

    public IrivMqttService(MqttPubSubServiceProvider mqttPubSubServiceProvider,
                            DeviceServiceProvider deviceServiceProvider,
                            EntityValueServiceProvider entityValueServiceProvider,
                            DeviceStatusServiceProvider deviceStatusServiceProvider) {
        this.mqttPubSubServiceProvider = mqttPubSubServiceProvider;
        this.deviceServiceProvider = deviceServiceProvider;
        this.entityValueServiceProvider = entityValueServiceProvider;
        this.deviceStatusServiceProvider = deviceStatusServiceProvider;
    }

    public void subscribe() {
        mqttPubSubServiceProvider.subscribe(TopicSupport.wildcardSubscribeTopic(), message ->
                TopicSupport.parse(message.getTopicSubPath()).ifPresent(parsed -> {
                    try {
                        onUplink(parsed.deviceIdentifier(), parsed.channelPath(), message.getPayload());
                    } catch (Exception e) {
                        log.warn("Failed to handle IRIV-IOC uplink on {}: {}", message.getTopicSubPath(), e.getMessage());
                    }
                }));

        // The gateway has no LWT/availability topic, so publish-recency (see onUplink below) was the only
        // signal for status - meaning a still-connected but momentarily quiet gateway could show OFFLINE,
        // and a genuinely disconnected one stayed ONLINE for up to DEFAULT_OFFLINE_TIMEOUT_SECONDS. The
        // broker itself always knows the true connection state of the gateway's own MQTT client though, so
        // react to that directly instead - same approach milesight-gateway uses for its own gateways.
        mqttPubSubServiceProvider.onConnect(this::onGatewayConnect);
        mqttPubSubServiceProvider.onDisconnect(this::onGatewayDisconnect);
    }

    public void unsubscribe() {
        mqttPubSubServiceProvider.unsubscribe(TopicSupport.wildcardSubscribeTopic());
    }

    private void onGatewayConnect(MqttConnectEvent event) {
        findDeviceByClientId(event.getClientId()).ifPresent(deviceStatusServiceProvider::online);
    }

    private void onGatewayDisconnect(MqttDisconnectEvent event) {
        findDeviceByClientId(event.getClientId()).ifPresent(deviceStatusServiceProvider::offline);
    }

    private Optional<Device> findDeviceByClientId(String clientId) {
        if (clientId == null || !clientId.startsWith(Constants.MQTT_CLIENT_ID_PREFIX)) {
            return Optional.empty(); // not one of our gateways
        }

        String identifier = clientId.substring(Constants.MQTT_CLIENT_ID_PREFIX.length());
        return Optional.ofNullable(deviceServiceProvider.findByIdentifier(identifier, Constants.INTEGRATION_ID));
    }

    private void onUplink(String deviceIdentifier, String channelPath, byte[] payload) {
        String[] parts = channelPath.split("/");
        if (parts.length != 2) {
            return; // not a channel we publish state for (e.g. a write-job confirm topic) - ignore
        }
        String channel = parts[0];

        Object value;
        if (BOOLEAN_CHANNELS.contains(channel)) {
            value = ValueCodec.decodeBoolean(payload);
        } else if (TopicSupport.CHANNEL_AI.equals(channel)) {
            value = ValueCodec.decodeAi(payload).orElse(null);
        } else if (TopicSupport.CHANNEL_MBPOLL.equals(channel)) {
            value = ValueCodec.decodePollJob(payload).orElse(null);
        } else {
            return;
        }
        if (value == null) {
            return; // unparseable or a non-"success" poll job status - nothing to report
        }

        Device device = deviceServiceProvider.findByIdentifier(deviceIdentifier, Constants.INTEGRATION_ID);
        if (device == null) {
            return;
        }

        String entityIdentifier = TopicSupport.entityIdentifier(channel, Integer.parseInt(parts[1]));
        String entityKey = IntegrationConstants.formatIntegrationDeviceEntityKey(device.getKey(), entityIdentifier);
        entityValueServiceProvider.saveValuesAndPublishAsync(ExchangePayload.create(Map.of(entityKey, value)), EVENT_TYPE_DEVICE_REPORT);
        deviceStatusServiceProvider.online(device);
    }
}
