package com.milesight.beaveriot.integrations.irivioc.util;

import com.milesight.beaveriot.integrations.irivioc.Constants;

import java.util.Optional;

/**
 * Builds/parses the MQTT topics used between Beaver IoT's embedded broker and an IRIV-IOC gateway.
 * <p>
 * We standardize every device on one fixed topic convention (channel type + index, e.g. {@code di/3},
 * {@code cmd/do/1}, {@code mbpoll/12}) and rewrite the gateway's own channel topicSuffix fields to match
 * whenever we provision/resync it (see {@code IrivDeviceService#pushSettings}). That means the mapping
 * between an entity and its topic is always computable from (channel type, index) alone - no per-device
 * topic table needs to be stored.
 * <p>
 * Each device's baseTopic on the gateway is set to {@code iriv-ioc-mqtt-gateway/<deviceIdentifier>}, so the
 * full topicSubPath (as delivered by {@code MqttMessage#getTopicSubPath()}) is
 * {@code iriv-ioc-mqtt-gateway/<deviceIdentifier>/<channelPath>}.
 */
public class TopicSupport {
    private TopicSupport() {
    }

    public static final String CHANNEL_DI = "di";
    public static final String CHANNEL_DO_STATE = "do";
    public static final String CHANNEL_DO_CMD = "cmd/do";
    public static final String CHANNEL_AI = "ai";
    public static final String CHANNEL_MBPOLL = "mbpoll";
    public static final String CHANNEL_MBWRITE = "mbwrite";

    public record ParsedTopic(String deviceIdentifier, String channelPath) {
    }

    public static String baseTopic(String deviceIdentifier) {
        return Constants.INTEGRATION_ID + "/" + deviceIdentifier;
    }

    public static String wildcardSubscribeTopic() {
        return Constants.INTEGRATION_ID + "/#";
    }

    public static String channelPath(String channel, int index) {
        return channel + "/" + index;
    }

    public static String entityIdentifier(String channel, int index) {
        return channel.replace("/", "_") + "_" + index;
    }

    public static String topicSubPath(String deviceIdentifier, String channel, int index) {
        return baseTopic(deviceIdentifier) + "/" + channelPath(channel, index);
    }

    /**
     * Splits an incoming topicSubPath (which already starts with {@value Constants#INTEGRATION_ID}) into
     * the device identifier and the remaining channel path (e.g. {@code di/3}).
     */
    public static Optional<ParsedTopic> parse(String topicSubPath) {
        String prefix = Constants.INTEGRATION_ID + "/";
        if (topicSubPath == null || !topicSubPath.startsWith(prefix)) {
            return Optional.empty();
        }
        String remainder = topicSubPath.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        return Optional.of(new ParsedTopic(remainder.substring(0, slash), remainder.substring(slash + 1)));
    }
}
