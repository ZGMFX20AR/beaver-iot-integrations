package com.milesight.beaveriot.integrations.edgeaistream.entity;

import com.milesight.beaveriot.context.integration.entity.annotation.Entity;
import com.milesight.beaveriot.context.integration.entity.annotation.IntegrationEntities;
import com.milesight.beaveriot.context.integration.enums.AccessMod;
import com.milesight.beaveriot.context.integration.enums.EntityType;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.integrations.edgeaistream.util.Constants;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Storage for this integration's own configuration.
 * <p>
 * The configured sources are kept as a JSON string in a single hidden PROPERTY entity,
 * the same way milesight-gateway stores its gateway/device relation map. It avoids a new
 * table for what is a short list edited by hand, and it inherits the tenant scoping the
 * entity store already applies.
 * <p>
 * Only non-secret fields live here. API keys are held by the credentials service instead,
 * because entity values are readable through the ordinary entity APIs and a key has no
 * business being retrievable that way.
 *
 * @author cytron
 */
@Data
@EqualsAndHashCode(callSuper = true)
@IntegrationEntities
public class EdgeAiStreamEntities extends ExchangePayload {
    public static final String SOURCES_IDENTIFIER = "sources";

    public static final String SOURCES_KEY = Constants.INTEGRATION_ID + ".integration." + SOURCES_IDENTIFIER;

    @Entity(type = EntityType.PROPERTY, name = "Camera Sources", identifier = SOURCES_IDENTIFIER,
            accessMod = AccessMod.R, visible = false)
    private String sources;
}
