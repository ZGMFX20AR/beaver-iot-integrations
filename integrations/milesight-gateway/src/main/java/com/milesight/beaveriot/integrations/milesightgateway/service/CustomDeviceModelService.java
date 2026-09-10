package com.milesight.beaveriot.integrations.milesightgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.context.api.DeviceServiceProvider;
import com.milesight.beaveriot.context.api.DeviceTemplateServiceProvider;
import com.milesight.beaveriot.context.integration.enums.AccessMod;
import com.milesight.beaveriot.context.integration.enums.EntityType;
import com.milesight.beaveriot.context.integration.enums.EntityValueType;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.context.integration.model.DeviceTemplateBuilder;
import com.milesight.beaveriot.context.integration.model.config.EntityConfig;
import com.milesight.beaveriot.context.model.DeviceTemplateModel;
import com.milesight.beaveriot.base.utils.YamlUtils;
import com.milesight.beaveriot.devicetemplate.facade.ICodecExecutorFacade;
import com.milesight.beaveriot.devicetemplate.facade.IDeviceCodecExecutorFacade;
import com.milesight.beaveriot.devicetemplate.facade.IDeviceTemplateParserFacade;
import com.milesight.beaveriot.integrations.milesightgateway.model.request.CustomDeviceModelRequest;
import com.milesight.beaveriot.integrations.milesightgateway.model.request.TestCodecRequest;
import com.milesight.beaveriot.integrations.milesightgateway.model.response.TestCodecResponse;
import com.milesight.beaveriot.integrations.milesightgateway.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages custom LoRaWAN device models, i.e. models defined by the user rather than
 * resolved from the blueprint library.
 * <p>
 * A custom model is stored as a device template owned by this integration with no
 * blueprint library id, which is what marks a template as custom. The template carries an
 * inline codec so binary uplinks can be decoded without any blueprint backing it.
 *
 * @author cytron
 */
@Component("milesightCustomDeviceModelService")
@Slf4j
public class CustomDeviceModelService {
    /**
     * Vendor id reserved for custom models, so they can share the model picker with
     * blueprint models while remaining distinguishable.
     */
    public static final String CUSTOM_VENDOR_ID = "__custom__";

    private static final String DEFAULT_CODEC_ENTRY = "decodeUplink";

    /**
     * Input key that identifies the device. Required by template validation.
     */
    private static final String DEVICE_ID_KEY = "device_id";

    private static final Set<String> SUPPORTED_LORA_CLASSES = Set.of("ClassA", "ClassB", "ClassC");

    /**
     * Value types the device template schema accepts; the entity model has more.
     */
    private static final Set<EntityValueType> SUPPORTED_VALUE_TYPES = Set.of(
            EntityValueType.STRING,
            EntityValueType.LONG,
            EntityValueType.DOUBLE,
            EntityValueType.BOOLEAN,
            EntityValueType.OBJECT);

    @Autowired
    DeviceTemplateServiceProvider deviceTemplateServiceProvider;

    @Autowired
    DeviceServiceProvider deviceServiceProvider;

    @Lazy
    @Autowired
    ICodecExecutorFacade codecExecutorFacade;

    @Lazy
    @Autowired
    IDeviceTemplateParserFacade deviceTemplateParserFacade;

    /**
     * Whether the given device model identifier refers to a custom model.
     */
    public static boolean isCustomModel(String vendorId) {
        return CUSTOM_VENDOR_ID.equals(vendorId);
    }

    public List<DeviceTemplate> listCustomModels() {
        List<DeviceTemplate> templates = deviceTemplateServiceProvider.findAllCustom(Constants.INTEGRATION_ID);
        return templates == null ? List.of() : templates;
    }

    public DeviceTemplate getByIdentifier(String identifier) {
        return deviceTemplateServiceProvider.findByIdentifier(identifier, Constants.INTEGRATION_ID);
    }

    /**
     * Create a custom device model. The identifier is derived from the name so the model
     * can be referenced from the device model picker.
     */
    public DeviceTemplate createCustomModel(CustomDeviceModelRequest request) {
        validate(request);

        String identifier = toIdentifier(request.getName());
        if (getByIdentifier(identifier) != null) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "A custom device model named '" + request.getName() + "' already exists").build();
        }

        DeviceTemplate deviceTemplate = new DeviceTemplateBuilder(Constants.INTEGRATION_ID)
                .name(request.getName())
                .description(request.getDescription())
                .identifier(identifier)
                .content(buildTemplateContent(request))
                .build();

        deviceTemplateServiceProvider.save(deviceTemplate);
        return deviceTemplate;
    }

    /**
     * Replace the definition of an existing custom model, then bring devices already created
     * from it up to date with the new definition.
     */
    public DeviceTemplate updateCustomModel(String identifier, CustomDeviceModelRequest request) {
        validate(request);

        DeviceTemplate existing = getByIdentifier(identifier);
        if (existing == null) {
            throw ServiceException.with(ErrorCode.DATA_NO_FOUND.getErrorCode(),
                    "Custom device model not found: " + identifier).build();
        }

        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setContent(buildTemplateContent(request));
        deviceTemplateServiceProvider.save(existing);
        applyToExistingDevices(existing);
        return existing;
    }

    /**
     * Push the model's current entity definitions onto the devices already created from it.
     * <p>
     * A device's entities are built once, when the device is created, so without this an edit
     * only ever reached devices created afterwards: changing an entity's unit updated the model
     * while every existing device kept displaying the old one, with nothing to explain the
     * mismatch. Resyncing per device is still available for the same job; this just means the
     * common case does not depend on the user knowing to go and click it.
     */
    private void applyToExistingDevices(DeviceTemplate deviceTemplate) {
        List<Device> devices = deviceServiceProvider.findAll(Constants.INTEGRATION_ID);
        if (CollectionUtils.isEmpty(devices)) {
            return;
        }

        for (Device device : devices) {
            if (!Objects.equals(device.getTemplate(), deviceTemplate.getKey())) {
                continue;
            }
            // The model itself is already saved, so one uncooperative device must not fail the
            // whole edit and leave the user unable to save at all - it just stays stale, and a
            // manual resync remains available for it.
            try {
                deviceTemplateParserFacade.resyncDeviceEntities(device.getKey());
            } catch (Exception e) {
                log.warn("Could not apply custom model '{}' to device {}",
                        deviceTemplate.getIdentifier(), device.getKey(), e);
            }
        }
    }

    public void deleteCustomModel(String identifier) {
        DeviceTemplate existing = getByIdentifier(identifier);
        if (existing == null) {
            throw ServiceException.with(ErrorCode.DATA_NO_FOUND.getErrorCode(),
                    "Custom device model not found: " + identifier).build();
        }

        deviceTemplateServiceProvider.deleteById(existing.getId());
    }

    /**
     * Decode a sample payload with an in-progress (possibly unsaved) codec, so the
     * editor can show real decode output/errors before the model is saved or a device
     * exists. Never throws - failures are reported via {@link TestCodecResponse#getErrorMessage()}
     * so a broken codec while editing is treated as an expected outcome, not a system error.
     */
    public TestCodecResponse testCodec(TestCodecRequest request) {
        if (request == null || StringUtils.isEmpty(request.getCodecCode())) {
            return TestCodecResponse.failed("Decoder source is required");
        }

        byte[] payload;
        try {
            payload = parseHexPayload(request.getPayloadHex());
        } catch (IllegalArgumentException e) {
            return TestCodecResponse.failed(e.getMessage());
        }

        DeviceTemplateModel model = new DeviceTemplateModel();
        DeviceTemplateModel.Codec codec = new DeviceTemplateModel.Codec();
        codec.setCode(request.getCodecCode());
        codec.setEntry(StringUtils.isEmpty(request.getCodecEntry()) ? DEFAULT_CODEC_ENTRY : request.getCodecEntry());
        model.setCodec(codec);

        IDeviceCodecExecutorFacade executor = codecExecutorFacade.getInlineDeviceCodecExecutor(model);
        if (executor == null) {
            return TestCodecResponse.failed("Could not build a decoder from the given code and entry function");
        }

        Map<String, Object> argContext = Map.of("fPort", request.getFPort() == null ? 0 : request.getFPort());
        try {
            JsonNode result = executor.decode(payload, argContext);
            return TestCodecResponse.ok(result);
        } catch (ServiceException e) {
            String detail = e.getDetailMessage();
            return TestCodecResponse.failed(StringUtils.isEmpty(detail) ? e.getMessage() : detail);
        } catch (Exception e) {
            log.error("Codec test failed", e);
            return TestCodecResponse.failed(e.getMessage());
        }
    }

    /**
     * Parses a hex-encoded sample payload, tolerating an optional "0x" prefix and
     * whitespace between bytes.
     */
    private static byte[] parseHexPayload(String hex) {
        if (StringUtils.isEmpty(hex)) {
            return new byte[0];
        }

        String cleaned = hex.trim().replaceAll("^0[xX]", "").replaceAll("\\s+", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex payload must have an even number of characters");
        }

        try {
            return HexFormat.of().parseHex(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Payload is not valid hex: " + e.getMessage());
        }
    }

    private void validate(CustomDeviceModelRequest request) {
        if (request == null || StringUtils.isEmpty(request.getName())) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Device model name is required").build();
        }

        if (StringUtils.isEmpty(request.getCodecCode())) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "A decoder is required: LoRaWAN uplinks are binary and cannot be mapped to entities without one").build();
        }

        if (CollectionUtils.isEmpty(request.getEntities())) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "At least one entity must be defined").build();
        }

        if (!StringUtils.isEmpty(request.getLoraClass()) && !SUPPORTED_LORA_CLASSES.contains(request.getLoraClass())) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "LoRa class must be one of " + SUPPORTED_LORA_CLASSES).build();
        }

        for (CustomDeviceModelRequest.EntityDefinition entity : request.getEntities()) {
            if (StringUtils.isEmpty(entity.getIdentifier())) {
                throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                        "Every entity needs an identifier").build();
            }
            parseValueType(entity);
        }
    }

    private EntityValueType parseValueType(CustomDeviceModelRequest.EntityDefinition entity) {
        EntityValueType valueType;
        try {
            valueType = EntityValueType.valueOf(entity.getValueType().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Unsupported value type '" + entity.getValueType() + "' for entity '" + entity.getIdentifier() + "'").build();
        }

        // The template schema accepts a narrower set than the entity model does.
        if (!SUPPORTED_VALUE_TYPES.contains(valueType)) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Value type '" + valueType + "' is not supported for a device template; use one of " + SUPPORTED_VALUE_TYPES).build();
        }
        return valueType;
    }

    /**
     * Build the device template document.
     * <p>
     * The document is assembled as plain maps rather than by serialising the typed model,
     * because Jackson writes Java enums as their uppercase names while the template schema
     * requires lowercase {@code type} and {@code value_type} values. Reading is tolerant of
     * either case, so the mismatch only surfaces as a schema validation failure.
     * It still goes through the YAML mapper so the embedded decoder source is escaped.
     */
    private String buildTemplateContent(CustomDeviceModelRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();

        if (!StringUtils.isEmpty(request.getLoraClass())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(Constants.LORA_CLASS_METADATA_KEY, request.getLoraClass());
            root.put("metadata", metadata);
        }

        Map<String, Object> codec = new LinkedHashMap<>();
        codec.put("entry", StringUtils.isEmpty(request.getCodecEntry()) ? DEFAULT_CODEC_ENTRY : request.getCodecEntry());
        if (!StringUtils.isEmpty(request.getCodecEncodeEntry())) {
            codec.put("encode_entry", request.getCodecEncodeEntry());
        }
        codec.put("code", request.getCodecCode());
        root.put("codec", codec);

        List<Map<String, Object>> inputProperties = new ArrayList<>();
        for (CustomDeviceModelRequest.EntityDefinition entity : request.getEntities()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("key", entity.getIdentifier());
            property.put("type", toSchemaValueType(entity));
            property.put("entity_mapping", entity.getIdentifier());
            if (DEVICE_ID_KEY.equals(entity.getIdentifier())) {
                // The user already models the device id; mark it rather than adding a second one.
                property.put("is_device_id", true);
            }
            inputProperties.add(property);
        }

        // The template must declare exactly one device id property. A LoRaWAN device is
        // identified by its DevEUI rather than by anything in the payload, and the parser
        // fills this key from the device identifier when the decoded data omits it, so a
        // synthetic property satisfies the rule without requiring an extra entity.
        boolean hasDeviceId = inputProperties.stream().anyMatch(p -> Boolean.TRUE.equals(p.get("is_device_id")));
        if (!hasDeviceId) {
            Map<String, Object> deviceIdProperty = new LinkedHashMap<>();
            deviceIdProperty.put("key", DEVICE_ID_KEY);
            deviceIdProperty.put("type", "string");
            deviceIdProperty.put("is_device_id", true);
            inputProperties.add(0, deviceIdProperty);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", inputProperties);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("input", input);
        root.put("definition", definition);

        root.put("initial_entities", buildEntities(request));

        return YamlUtils.toYAML(root);
    }

    private List<Map<String, Object>> buildEntities(CustomDeviceModelRequest request) {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (CustomDeviceModelRequest.EntityDefinition entity : request.getEntities()) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("identifier", entity.getIdentifier());
            config.put("name", StringUtils.isEmpty(entity.getName()) ? entity.getIdentifier() : entity.getName());
            config.put("type", "property");
            config.put("value_type", toSchemaValueType(entity));
            // Uplink-reported values are read-only from the platform's point of view.
            config.put("access_mod", AccessMod.R.name());

            Map<String, Object> attributes = new LinkedHashMap<>();
            if (!StringUtils.isEmpty(entity.getUnit())) {
                attributes.put("unit", entity.getUnit());
            }
            Map<String, Object> valueEnum = buildBooleanEnum(entity);
            if (valueEnum != null) {
                attributes.put("enum", valueEnum);
            }
            if (!attributes.isEmpty()) {
                config.put("attributes", attributes);
            }

            entities.add(config);
        }
        return entities;
    }

    /**
     * The {@code enum} attribute for a BOOLEAN entity that declares state labels, or null.
     * <p>
     * Keys are the literal strings "false" and "true", matching what blueprint models emit
     * and what the dashboard widgets look the current value up by. Both labels have to be
     * present to be worth writing: a half-filled enum would leave one state rendering as a
     * bare true/false while the other showed a label.
     */
    private Map<String, Object> buildBooleanEnum(CustomDeviceModelRequest.EntityDefinition entity) {
        if (parseValueType(entity) != EntityValueType.BOOLEAN
                || StringUtils.isEmpty(entity.getTrueLabel())
                || StringUtils.isEmpty(entity.getFalseLabel())) {
            return null;
        }

        Map<String, Object> valueEnum = new LinkedHashMap<>();
        valueEnum.put("false", entity.getFalseLabel());
        valueEnum.put("true", entity.getTrueLabel());
        return valueEnum;
    }

    /**
     * Schema-facing name of the entity's value type, which is lowercase.
     */
    private String toSchemaValueType(CustomDeviceModelRequest.EntityDefinition entity) {
        return parseValueType(entity).name().toLowerCase(Locale.ROOT);
    }

    /**
     * Derive a stable identifier from the display name.
     */
    private String toIdentifier(String name) {
        String identifier = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (StringUtils.isEmpty(identifier)) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                    "Device model name must contain at least one letter or digit").build();
        }
        return identifier;
    }
}
