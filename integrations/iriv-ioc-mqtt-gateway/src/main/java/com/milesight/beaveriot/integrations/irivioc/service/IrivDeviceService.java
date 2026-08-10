package com.milesight.beaveriot.integrations.irivioc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.milesight.beaveriot.context.api.CredentialsServiceProvider;
import com.milesight.beaveriot.context.api.DeviceServiceProvider;
import com.milesight.beaveriot.context.api.MqttPubSubServiceProvider;
import com.milesight.beaveriot.context.constants.IntegrationConstants;
import com.milesight.beaveriot.context.integration.enums.AccessMod;
import com.milesight.beaveriot.context.integration.enums.CredentialsType;
import com.milesight.beaveriot.context.integration.enums.EntityValueType;
import com.milesight.beaveriot.context.integration.model.*;
import com.milesight.beaveriot.context.integration.model.event.ExchangeEvent;
import com.milesight.beaveriot.context.mqtt.enums.MqttQos;
import com.milesight.beaveriot.context.mqtt.model.MqttBrokerInfo;
import com.milesight.beaveriot.eventbus.annotations.EventSubscribe;
import com.milesight.beaveriot.eventbus.api.Event;
import com.milesight.beaveriot.integrations.irivioc.Constants;
import com.milesight.beaveriot.integrations.irivioc.client.IrivGatewayHttpClient;
import com.milesight.beaveriot.integrations.irivioc.client.model.IrivConfig;
import com.milesight.beaveriot.integrations.irivioc.entity.IrivIntegrationEntities;
import com.milesight.beaveriot.integrations.irivioc.util.TopicSupport;
import com.milesight.beaveriot.integrations.irivioc.util.ValueCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Handles device lifecycle for IRIV-IOC gateways: adding one runs REST discovery against the gateway's own
 * admin API and takes over its MQTT wiring; deleting one just removes the Beaver IoT device; per-device
 * actions (resync channels, push MQTT settings, DO/write-job control) come in through the generic
 * device-scoped entity event channel since the channel set is built dynamically per device.
 */
@Slf4j
@Service
public class IrivDeviceService {
    public static final String RESYNC_CHANNELS_IDENTIFIER = "resync_channels";
    public static final String PUSH_MQTT_SETTINGS_IDENTIFIER = "push_mqtt_settings";

    @Autowired
    private DeviceServiceProvider deviceServiceProvider;

    @Autowired
    private MqttPubSubServiceProvider mqttPubSubServiceProvider;

    @Autowired
    private CredentialsServiceProvider credentialsServiceProvider;

    @Autowired
    private IrivGatewayHttpClient gatewayHttpClient;

    @EventSubscribe(payloadKeyExpression = Constants.INTEGRATION_ID + ".integration." + IrivIntegrationEntities.ADD_DEVICE_IDENTIFIER + ".*", eventType = ExchangeEvent.EventType.CALL_SERVICE)
    public void onAddDevice(Event<IrivIntegrationEntities.AddDevice> event) {
        IrivIntegrationEntities.AddDevice addDevice = event.getPayload();
        String host = addDevice.getHost();
        String deviceName = addDevice.getAddDeviceName();
        // Identity is independent of the gateway's IP - it's DHCP-assigned on this device (confirmed
        // live) and the firmware exposes no MAC/serial to key off instead, so an IP-derived identifier
        // would silently break resync/push-settings (which log back in via the last-known host) on the
        // next lease renewal, even though the MQTT data path itself (topic/clientId-based) wouldn't care.
        String identifier = slugify(deviceName) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        IrivGatewayHttpClient.Session session = gatewayHttpClient.login(host, addDevice.getUsername(), addDevice.getPassword());
        String configJson = gatewayHttpClient.getConfigJson(session);
        IrivConfig config = gatewayHttpClient.parseConfig(configJson);

        pushSettings(session, identifier, configJson);

        String deviceKey = IntegrationConstants.formatIntegrationDeviceKey(Constants.INTEGRATION_ID, identifier);
        Device device = new DeviceBuilder(Constants.INTEGRATION_ID)
                .name(deviceName)
                .identifier(identifier)
                .additional(Map.of(
                        Constants.ADDITIONAL_HOST, host,
                        Constants.ADDITIONAL_USERNAME, addDevice.getUsername(),
                        Constants.ADDITIONAL_PASSWORD, addDevice.getPassword()
                ))
                .entities(buildEntities(deviceKey, config))
                .build();

        deviceServiceProvider.save(device);
    }

    @EventSubscribe(payloadKeyExpression = Constants.INTEGRATION_ID + ".integration." + IrivIntegrationEntities.DELETE_DEVICE_IDENTIFIER, eventType = ExchangeEvent.EventType.CALL_SERVICE)
    public void onDeleteDevice(Event<IrivIntegrationEntities.DeleteDevice> event) {
        Device device = event.getPayload().getDeletedDevice();
        deviceServiceProvider.deleteById(device.getId());
    }

    @EventSubscribe(payloadKeyExpression = Constants.INTEGRATION_ID + ".device.*", eventType = {
            ExchangeEvent.EventType.CALL_SERVICE, ExchangeEvent.EventType.UPDATE_PROPERTY})
    public void onDeviceEntityWrite(ExchangeEvent event) {
        Map<String, Object> allPayloads = event.getPayload().getAllPayloads();
        Map<String, Entity> entityMap = event.getPayload().getExchangeEntities();

        allPayloads.forEach((entityKey, value) -> {
            Entity entity = entityMap.get(entityKey);
            if (entity == null || entity.getDeviceKey() == null) {
                return;
            }

            Device device = deviceServiceProvider.findByKey(entity.getDeviceKey());
            if (device == null) {
                return;
            }

            String identifier = entity.getIdentifier();
            if (RESYNC_CHANNELS_IDENTIFIER.equals(identifier)) {
                resyncChannels(device);
            } else if (PUSH_MQTT_SETTINGS_IDENTIFIER.equals(identifier)) {
                pushSettingsForDevice(device);
            } else {
                publishChannelWrite(device, identifier, value);
            }
        });
    }

    public record ConnectionPreview(String deviceName, int diCount, int doCount, int aiCount, int pollJobCount, int writeJobCount) {
    }

    /**
     * Logs in and reads config only - no {@link #pushSettings} call, no device creation - so the wizard's
     * "Test Connection" step can show what will be found before the user commits to adding the device.
     */
    public ConnectionPreview previewConnection(String host, String username, String password) {
        IrivGatewayHttpClient.Session session = gatewayHttpClient.login(host, username, password);
        IrivConfig config = gatewayHttpClient.parseConfig(gatewayHttpClient.getConfigJson(session));

        IrivConfig.Io io = config.getIo();
        IrivConfig.Rtu rtu = config.getRtu();
        return new ConnectionPreview(
                config.getSystem() == null ? null : config.getSystem().getDeviceName(),
                countEnabled(io == null ? null : io.getDi(), IrivConfig.Di::isEnabled),
                countEnabled(io == null ? null : io.getDoOut(), IrivConfig.DoChannel::isEnabled),
                countEnabled(io == null ? null : io.getAi(), IrivConfig.Ai::isEnabled),
                countEnabled(rtu == null ? null : rtu.getPollJobs(), IrivConfig.PollJob::isEnabled),
                countEnabled(rtu == null ? null : rtu.getWriteJobs(), IrivConfig.WriteJob::isEnabled)
        );
    }

    private <T> int countEnabled(List<T> list, java.util.function.Predicate<T> enabled) {
        if (CollectionUtils.isEmpty(list)) {
            return 0;
        }
        return (int) list.stream().filter(enabled).count();
    }

    private void publishChannelWrite(Device device, String entityIdentifier, Object value) {
        String channel;
        int index;
        if (entityIdentifier.startsWith("do_")) {
            channel = TopicSupport.CHANNEL_DO_CMD;
            index = parseIndex(entityIdentifier);
        } else if (entityIdentifier.startsWith("mbwrite_")) {
            channel = TopicSupport.CHANNEL_MBWRITE;
            index = parseIndex(entityIdentifier);
        } else {
            log.warn("Ignoring write to non-writable IRIV-IOC entity {}", entityIdentifier);
            return;
        }

        byte[] payload = value instanceof Boolean bool ? ValueCodec.encode(bool) : ValueCodec.encode(((Number) value).doubleValue());
        String topicSubPath = TopicSupport.topicSubPath(device.getIdentifier(), channel, index);
        mqttPubSubServiceProvider.publish(topicSubPath, payload, MqttQos.AT_LEAST_ONCE, false);
    }

    private void resyncChannels(Device device) {
        IrivGatewayHttpClient.Session session = login(device);
        IrivConfig config = gatewayHttpClient.parseConfig(gatewayHttpClient.getConfigJson(session));

        String deviceKey = device.getKey();
        Device updated = new DeviceBuilder(Constants.INTEGRATION_ID)
                .id(device.getId())
                .name(device.getName())
                .identifier(device.getIdentifier())
                .additional(device.getAdditional())
                .entities(buildEntities(deviceKey, config))
                .build();
        deviceServiceProvider.save(updated);
    }

    private void pushSettingsForDevice(Device device) {
        IrivGatewayHttpClient.Session session = login(device);
        pushSettings(session, device.getIdentifier(), gatewayHttpClient.getConfigJson(session));
    }

    private IrivGatewayHttpClient.Session login(Device device) {
        Map<String, Object> additional = device.getAdditional();
        String host = (String) additional.get(Constants.ADDITIONAL_HOST);
        String username = (String) additional.get(Constants.ADDITIONAL_USERNAME);
        String password = (String) additional.get(Constants.ADDITIONAL_PASSWORD);
        return gatewayHttpClient.login(host, username, password);
    }

    /**
     * Rewrites the gateway's own MQTT + channel-topic config so it (a) points at Beaver IoT's embedded
     * broker and (b) uses our fixed topic convention (see {@link TopicSupport}), mutating a full config
     * tree fetched moments earlier so every other field the firmware doesn't default sensibly is
     * preserved verbatim.
     */
    private void pushSettings(IrivGatewayHttpClient.Session session, String identifier, String configJson) {
        JsonNode root = gatewayHttpClient.parseConfigTree(configJson);

        ObjectNode mqttNode = (ObjectNode) root.get("mqtt");
        MqttBrokerInfo brokerInfo = mqttPubSubServiceProvider.getMqttBrokerInfo();
        Credentials mqttCredentials = credentialsServiceProvider.getOrCreateCredentials(CredentialsType.MQTT);
        mqttNode.put("enabled", true);
        mqttNode.put("host", brokerInfo.getHost());
        mqttNode.put("port", brokerInfo.getMqttPort());
        mqttNode.put("clientId", Constants.MQTT_CLIENT_ID_PREFIX + identifier);
        // The gateway is a plain MQTT client - unlike our own subscribe/publish calls (which get the
        // 'beaver-iot/{username}/' prefix injected by MqttPubSubServiceProvider), it must be told the full
        // wire-level topic, otherwise the broker's per-user ACL rejects its publishes and it never connects.
        mqttNode.put("baseTopic", mqttPubSubServiceProvider.getFullTopicName(mqttCredentials.getAccessKey(), TopicSupport.baseTopic(identifier)));
        mqttNode.put("useAuth", true);
        mqttNode.put("user", mqttCredentials.getAccessKey());
        mqttNode.put("pass", mqttCredentials.getAccessSecret());

        ObjectNode ioNode = (ObjectNode) root.get("io");
        rewriteIndexed((ArrayNode) ioNode.get("di"),
                (item, i) -> item.put("topicSuffix", TopicSupport.channelPath(TopicSupport.CHANNEL_DI, i)));
        rewriteIndexed((ArrayNode) ioNode.get("doOut"), (item, i) -> {
            item.put("cmdTopicSuffix", TopicSupport.channelPath(TopicSupport.CHANNEL_DO_CMD, i));
            item.put("stateTopicSuffix", TopicSupport.channelPath(TopicSupport.CHANNEL_DO_STATE, i));
        });
        rewriteIndexed((ArrayNode) ioNode.get("ai"),
                (item, i) -> item.put("topicSuffix", TopicSupport.channelPath(TopicSupport.CHANNEL_AI, i)));

        // Poll jobs always publish a JSON envelope regardless of their "payload" field (confirmed live -
        // the field isn't even persisted by this firmware), so we don't touch it - see ValueCodec.decodePollJob.
        ObjectNode rtuNode = (ObjectNode) root.get("rtu");
        rewriteIndexed((ArrayNode) rtuNode.get("pollJobs"),
                (item, i) -> item.put("topicSuffix", TopicSupport.channelPath(TopicSupport.CHANNEL_MBPOLL, i)));
        rewriteIndexed((ArrayNode) rtuNode.get("writeJobs"),
                (item, i) -> item.put("subscribeTopic", TopicSupport.channelPath(TopicSupport.CHANNEL_MBWRITE, i)));

        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        patch.set("mqtt", mqttNode);
        patch.set("io", ioNode);
        patch.set("rtu", rtuNode);
        gatewayHttpClient.postConfig(session, patch);
    }

    private void rewriteIndexed(ArrayNode array, BiConsumer<ObjectNode, Integer> mutator) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) instanceof ObjectNode item) {
                mutator.accept(item, i);
            }
        }
    }

    private List<Entity> buildEntities(String deviceKey, IrivConfig config) {
        List<Entity> entities = new ArrayList<>();

        IrivConfig.Io io = config.getIo();
        List<IrivConfig.Di> diList = io == null ? null : io.getDi();
        forEachEnabled(diList, IrivConfig.Di::isEnabled, (di, i) -> entities.add(
                new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                        .identifier(TopicSupport.entityIdentifier(TopicSupport.CHANNEL_DI, i))
                        .property(nameOr(di.getName(), "DI " + i), AccessMod.R)
                        .valueType(EntityValueType.BOOLEAN)
                        .build()));

        List<IrivConfig.DoChannel> doList = io == null ? null : io.getDoOut();
        forEachEnabled(doList, IrivConfig.DoChannel::isEnabled, (doChannel, i) -> entities.add(
                new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                        .identifier(TopicSupport.entityIdentifier(TopicSupport.CHANNEL_DO_STATE, i))
                        .property(nameOr(doChannel.getName(), "DO " + i), AccessMod.RW)
                        .valueType(EntityValueType.BOOLEAN)
                        .build()));

        List<IrivConfig.Ai> aiList = io == null ? null : io.getAi();
        forEachEnabled(aiList, IrivConfig.Ai::isEnabled, (ai, i) -> entities.add(
                new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                        .identifier(TopicSupport.entityIdentifier(TopicSupport.CHANNEL_AI, i))
                        .property(nameOr(ai.getName(), "AI " + i), AccessMod.R)
                        .valueType(EntityValueType.DOUBLE)
                        .build()));

        IrivConfig.Rtu rtu = config.getRtu();
        List<IrivConfig.PollJob> pollJobs = rtu == null ? null : rtu.getPollJobs();
        forEachEnabled(pollJobs, IrivConfig.PollJob::isEnabled, (job, i) -> entities.add(
                new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                        .identifier(TopicSupport.entityIdentifier(TopicSupport.CHANNEL_MBPOLL, i))
                        .property(nameOr(job.getName(), "Modbus Poll " + i), AccessMod.R)
                        .valueType(EntityValueType.DOUBLE)
                        .attributes(new AttributeBuilder().unit(job.getUnit()).fractionDigits(job.getDecimals()).build())
                        .build()));

        List<IrivConfig.WriteJob> writeJobs = rtu == null ? null : rtu.getWriteJobs();
        forEachEnabled(writeJobs, IrivConfig.WriteJob::isEnabled, (job, i) -> entities.add(
                new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                        .identifier(TopicSupport.entityIdentifier(TopicSupport.CHANNEL_MBWRITE, i))
                        .property(nameOr(job.getName(), "Modbus Write " + i), AccessMod.RW)
                        .valueType(EntityValueType.DOUBLE)
                        .attributes(new AttributeBuilder().min(job.getClampMin()).max(job.getClampMax()).build())
                        .build()));

        entities.add(new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                .identifier(RESYNC_CHANNELS_IDENTIFIER)
                .service("Resync Channels")
                .valueType(EntityValueType.OBJECT)
                .build());
        entities.add(new EntityBuilder(Constants.INTEGRATION_ID, deviceKey)
                .identifier(PUSH_MQTT_SETTINGS_IDENTIFIER)
                .service("Push MQTT Settings to Gateway")
                .valueType(EntityValueType.OBJECT)
                .build());

        return entities;
    }

    private <T> void forEachEnabled(List<T> list, java.util.function.Predicate<T> enabled, BiConsumer<T, Integer> consumer) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            T item = list.get(i);
            if (enabled.test(item)) {
                consumer.accept(item, i);
            }
        }
    }

    private String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private int parseIndex(String entityIdentifier) {
        return Integer.parseInt(entityIdentifier.substring(entityIdentifier.lastIndexOf('_') + 1));
    }

    private String slugify(String host) {
        return host.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
