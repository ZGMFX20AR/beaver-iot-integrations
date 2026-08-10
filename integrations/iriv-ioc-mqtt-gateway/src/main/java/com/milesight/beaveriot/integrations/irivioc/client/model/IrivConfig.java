package com.milesight.beaveriot.integrations.irivioc.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Mirrors the JSON shape returned by the IRIV-IOC MQTT Gateway's {@code GET /api/config}.
 * Field names match the gateway firmware's own config schema (see its {@code app.js}), not our platform's
 * naming conventions - keep them as-is so Jackson can bind directly.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IrivConfig {
    private Mqtt mqtt;
    private Io io;
    private Rtu rtu;
    private System system;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Mqtt {
        private boolean enabled;
        private String host;
        private Integer port;
        private String clientId;
        private String baseTopic;
        private Integer keepAlive;
        private boolean cleanSession;
        private boolean useAuth;
        private String user;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Io {
        private List<Di> di;
        private List<DoChannel> doOut;
        private List<Ai> ai;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Di {
        private boolean enabled;
        private String name;
        private String topicSuffix;
        private int qos;
        private boolean retain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoChannel {
        private boolean enabled;
        private String name;
        private String cmdTopicSuffix;
        private String stateTopicSuffix;
        private int qos;
        private boolean retain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ai {
        private boolean enabled;
        private String name;
        private String topicSuffix;
        private int qos;
        private boolean retain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rtu {
        private List<PollJob> pollJobs;
        private List<WriteJob> writeJobs;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PollJob {
        private boolean enabled;
        private String name;
        private String unit;
        private Integer decimals;
        private String topicSuffix;
        private int qos;
        private boolean retain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WriteJob {
        private boolean enabled;
        private String name;
        private String subscribeTopic;
        private String confirmTopic;
        private Double clampMin;
        private Double clampMax;
        private int qos;
        private boolean retain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class System {
        private String deviceName;
    }
}
