package com.milesight.beaveriot.integrations.irivioc.entity;

import com.milesight.beaveriot.context.integration.context.AddDeviceAware;
import com.milesight.beaveriot.context.integration.context.DeleteDeviceAware;
import com.milesight.beaveriot.context.integration.entity.annotation.Attribute;
import com.milesight.beaveriot.context.integration.entity.annotation.Entities;
import com.milesight.beaveriot.context.integration.entity.annotation.Entity;
import com.milesight.beaveriot.context.integration.entity.annotation.IntegrationEntities;
import com.milesight.beaveriot.context.integration.enums.EntityType;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Integration-level (not per-device) entities: only the generic Add/Delete Device service calls that
 * Beaver IoT's stock UI wires up automatically from {@code integration.yaml}'s
 * {@code entity-identifier-add-device}/{@code entity-identifier-delete-device}.
 * <p>
 * Per-device actions (resync channels, push MQTT settings to the gateway) are built as ordinary SERVICE
 * entities on the device itself - see {@code IrivDeviceService} - since they only make sense once a device
 * exists.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@IntegrationEntities
public class IrivIntegrationEntities extends ExchangePayload {
    public static final String ADD_DEVICE_IDENTIFIER = "add_device";
    public static final String DELETE_DEVICE_IDENTIFIER = "delete_device";

    @Entity(type = EntityType.SERVICE, identifier = ADD_DEVICE_IDENTIFIER, visible = false)
    private AddDevice addDevice;

    @Entity(type = EntityType.SERVICE, identifier = DELETE_DEVICE_IDENTIFIER, visible = false)
    private DeleteDevice deleteDevice;

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Entities
    public static class AddDevice extends ExchangePayload implements AddDeviceAware {
        @Entity(name = "Gateway IP / Host", identifier = "host", attributes = @Attribute(maxLength = 253))
        private String host;

        @Entity(name = "Admin Username", identifier = "username", attributes = @Attribute(maxLength = 64))
        private String username;

        @Entity(name = "Admin Password", identifier = "password", attributes = @Attribute(maxLength = 128))
        private String password;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Entities
    public static class DeleteDevice extends ExchangePayload implements DeleteDeviceAware {
    }
}
