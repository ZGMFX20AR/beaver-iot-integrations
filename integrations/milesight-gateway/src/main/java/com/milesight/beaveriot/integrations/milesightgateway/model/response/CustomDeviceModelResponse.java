package com.milesight.beaveriot.integrations.milesightgateway.model.response;

import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.base.utils.YamlUtils;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.context.model.DeviceTemplateModel;
import com.milesight.beaveriot.integrations.milesightgateway.model.DeviceModelIdentifier;
import com.milesight.beaveriot.integrations.milesightgateway.service.CustomDeviceModelService;
import com.milesight.beaveriot.integrations.milesightgateway.util.Constants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * A custom device model as shown in the gateway UI.
 *
 * @author cytron
 */
@Data
@Slf4j
public class CustomDeviceModelResponse {
    private Long id;

    private String identifier;

    private String name;

    private String description;

    /**
     * Value to pass as the device model when adding a device with this model.
     */
    private String deviceModelId;

    /**
     * Raw template document.
     */
    private String content;

    /**
     * LoRaWAN class, read back out of the template metadata.
     */
    private String loraClass;

    /**
     * Decoder source and entry, so the editor can round-trip an existing definition
     * instead of the user having to retype it.
     */
    private String codecCode;

    private String codecEntry;

    private String codecEncodeEntry;

    /**
     * Entities declared by the model, in the order they were defined.
     */
    private List<EntityDefinition> entities;

    private Long createdAt;

    private Long updatedAt;

    @Data
    public static class EntityDefinition {
        private String identifier;
        private String name;
        private String valueType;
        private String unit;
    }

    public static CustomDeviceModelResponse of(DeviceTemplate deviceTemplate) {
        CustomDeviceModelResponse response = new CustomDeviceModelResponse();
        response.setId(deviceTemplate.getId());
        response.setIdentifier(deviceTemplate.getIdentifier());
        response.setName(deviceTemplate.getName());
        response.setDescription(deviceTemplate.getDescription());
        response.setDeviceModelId(new DeviceModelIdentifier(
                CustomDeviceModelService.CUSTOM_VENDOR_ID, deviceTemplate.getIdentifier()).toString());
        response.setContent(deviceTemplate.getContent());
        response.setCreatedAt(deviceTemplate.getCreatedAt());
        response.setUpdatedAt(deviceTemplate.getUpdatedAt());
        response.fillFromTemplate(deviceTemplate.getContent());
        return response;
    }

    /**
     * Decompose the stored template back into the fields the editor form is built from.
     * A template that cannot be parsed still yields a usable summary, just without the
     * editable detail.
     */
    private void fillFromTemplate(String content) {
        if (StringUtils.isEmpty(content)) {
            return;
        }

        DeviceTemplateModel model;
        try {
            model = YamlUtils.fromYAML(content, DeviceTemplateModel.class);
        } catch (Exception e) {
            log.warn("Unable to parse custom device model template: {}", e.getMessage());
            return;
        }

        if (model == null) {
            return;
        }

        if (model.getMetadata() != null) {
            Object loraClassValue = model.getMetadata().get(Constants.LORA_CLASS_METADATA_KEY);
            if (loraClassValue != null) {
                setLoraClass(String.valueOf(loraClassValue));
            }
        }

        DeviceTemplateModel.Codec codec = model.getCodec();
        if (codec != null) {
            setCodecCode(codec.getCode());
            setCodecEntry(codec.getEntry());
            setCodecEncodeEntry(codec.getEncodeEntry());
        }

        if (!CollectionUtils.isEmpty(model.getInitialEntities())) {
            setEntities(model.getInitialEntities().stream().map(entityConfig -> {
                EntityDefinition entity = new EntityDefinition();
                entity.setIdentifier(entityConfig.getIdentifier());
                entity.setName(entityConfig.getName());
                entity.setValueType(entityConfig.getValueType() == null
                        ? null : entityConfig.getValueType().name());
                if (entityConfig.getAttributes() != null) {
                    Object unit = entityConfig.getAttributes().get("unit");
                    entity.setUnit(unit == null ? null : String.valueOf(unit));
                }
                return entity;
            }).toList());
        }
    }
}
