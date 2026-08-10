package com.milesight.beaveriot.integrations.milesightgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milesight.beaveriot.base.annotations.shedlock.DistributedLock;
import com.milesight.beaveriot.base.enums.ErrorCode;
import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.context.api.DeviceTemplateParserProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.integrations.milesightgateway.model.DeviceModelIdentifier;
import com.milesight.beaveriot.integrations.milesightgateway.model.GatewayData;
import com.milesight.beaveriot.integrations.milesightgateway.model.GatewayDeviceData;
import com.milesight.beaveriot.integrations.milesightgateway.model.GatewayDeviceOperation;
import com.milesight.beaveriot.integrations.milesightgateway.model.api.DeviceListItemFields;
import com.milesight.beaveriot.integrations.milesightgateway.model.request.SyncDeviceItem;
import com.milesight.beaveriot.integrations.milesightgateway.model.request.SyncGatewayDeviceRequest;
import com.milesight.beaveriot.integrations.milesightgateway.model.response.SyncDeviceListItem;
import com.milesight.beaveriot.integrations.milesightgateway.requester.GatewayRequester;
import com.milesight.beaveriot.integrations.milesightgateway.requester.GatewayRequesterFactory;
import com.milesight.beaveriot.integrations.milesightgateway.util.Constants;
import com.milesight.beaveriot.integrations.milesightgateway.util.GatewayString;
import com.milesight.beaveriot.integrations.milesightgateway.util.LockConstants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static com.milesight.beaveriot.integrations.milesightgateway.mqtt.MsGwMqttClient.GATEWAY_REQUEST_BATCH_SIZE;

/**
 * SyncGatewayDeviceService class.
 *
 * @author simon
 * @date 2025/3/13
 */
@Component("milesightSyncGatewayDeviceService")
@Slf4j
public class SyncGatewayDeviceService {
    @Autowired
    GatewayRequesterFactory gatewayRequesterFactory;

    @Autowired
    GatewayService gatewayService;

    @Autowired
    DeviceService deviceService;

    @Autowired
    CustomDeviceModelService customDeviceModelService;

    @Autowired
    DeviceModelService deviceModelService;

    @Autowired
    MsGwEntityService msGwEntityService;

    @Autowired
    TaskExecutor taskExecutor;

    @Autowired
    DeviceTemplateParserProvider deviceTemplateParserProvider;

    @Autowired
    EntityValueServiceProvider entityValueServiceProvider;

    private static final ObjectMapper json = GatewayString.jsonInstance();

    private static final String NONE_CODEC_ID = "0";

    public List<SyncDeviceListItem> getGatewayDeviceSyncList(String gatewayEui) {
        Device gateway = gatewayService.getGatewayByEui(gatewayEui);
        if (gateway == null) {
            throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(), "gateway not found: " + gatewayEui).build();
        }

        List<Map<String, Object>> deviceDataMap = gatewayRequesterFactory.create(GatewayData.fromMap(gateway.getAdditional())).requestAllDeviceList();
        if (deviceDataMap.isEmpty()) {
            return List.of();
        }

        List<String> existedDeviceEui = msGwEntityService.getGatewayRelation().get(gatewayEui);
        Set<String> existedDeviceEuiSet = new HashSet<>();
        if (!ObjectUtils.isEmpty(existedDeviceEui)) {
            existedDeviceEuiSet.addAll(existedDeviceEui);
        }

        Map<String, String> modelNameToId = deviceModelService.getDeviceModelNameToId();
        return deviceDataMap.stream()
                .filter(deviceData -> {
                    String eui = GatewayString.standardizeEUI((String) deviceData.get(DeviceListItemFields.DEV_EUI));
                    return !existedDeviceEuiSet.contains(eui);
                })
                .map(deviceData -> {
                    SyncDeviceListItem item = new SyncDeviceListItem();
                    item.setEui(GatewayString.standardizeEUI((String) deviceData.get(DeviceListItemFields.DEV_EUI)));
                    item.setName((String) deviceData.get(DeviceListItemFields.NAME));
                    String codecName = (String) deviceData.get(DeviceListItemFields.PAYLOAD_NAME);
                    if (codecName != null) {
                        item.setGuessModelId(modelNameToId.get(codecName));
                    }
                    return item;
                })
                .toList();
    }

    @Data
    private static class UpdateGatewayDeviceResponse {
        GatewayDeviceData deviceData;
        String deviceName;
    }

    private Map<String, Long> resolveOfflineTimeouts(List<SyncDeviceItem> devices) {
        Map<String, Long> result = new HashMap<>();
        devices.forEach(syncRequest -> {
            Long offlineTimeout = syncRequest.getOfflineTimeout();
            if (offlineTimeout == null) {
                result.put(syncRequest.getEui(), Constants.DEFAULT_DEVICE_OFFLINE_TIMEOUT);
                return;
            }
            if (offlineTimeout < Constants.OFFLINE_TIMEOUT_ENTITY_MIN_VALUE || offlineTimeout > Constants.OFFLINE_TIMEOUT_ENTITY_MAX_VALUE) {
                throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                        "Offline timeout for device " + syncRequest.getEui() + " must be between "
                                + Constants.OFFLINE_TIMEOUT_ENTITY_MIN_VALUE + " and " + Constants.OFFLINE_TIMEOUT_ENTITY_MAX_VALUE + " minutes")
                        .build();
            }
            result.put(syncRequest.getEui(), offlineTimeout);
        });
        return result;
    }

    @DistributedLock(name = LockConstants.SYNC_GATEWAY_DEVICE_LOCK)
    public void syncGatewayDevice(String gatewayEui, SyncGatewayDeviceRequest request) {
        Device gateway = gatewayService.getGatewayByEui(gatewayEui);
        GatewayRequester gatewayRequester = gatewayRequesterFactory.create(GatewayData.fromMap(gateway.getAdditional()));

        // check connection of gateway. In case a large number of doomed-to-fail requests were sent.
        gatewayRequester.requestBase();

        // validate offline timeouts up front so a bad value fails before any gateway calls
        Map<String, Long> offlineTimeoutsByEui = resolveOfflineTimeouts(request.getDevices());

        // batch reset device codec
        List<UpdateGatewayDeviceResponse> deviceItemList = new ArrayList<>();
        int offset = 0;
        while (offset < request.getDevices().size()) {
            int end = Math.min(request.getDevices().size(), offset + GATEWAY_REQUEST_BATCH_SIZE);
            List<CompletableFuture<UpdateGatewayDeviceResponse>> futures = request.getDevices()
                    .subList(offset, end)
                    .stream()
                    .map(syncRequest -> CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> deviceItemData = gatewayService.doUpdateGatewayDevice(gatewayRequester, syncRequest.getEui(), Map.of(
                                DeviceListItemFields.PAYLOAD_CODEC_ID, NONE_CODEC_ID,
                                DeviceListItemFields.PAYLOAD_NAME, ""
                        ));
                        UpdateGatewayDeviceResponse response = new UpdateGatewayDeviceResponse();
                        if (ObjectUtils.isEmpty(deviceItemData)) {
                            return response;
                        }

                        response.setDeviceName((String) deviceItemData.get(DeviceListItemFields.NAME));
                        GatewayDeviceData deviceData = new GatewayDeviceData();
                        deviceData.setEui(syncRequest.getEui());
                        deviceData.setGatewayEUI(gatewayEui);
                        deviceData.setDeviceModel(syncRequest.getModelId());
                        deviceData.setFPort(GatewayString.jsonInstance().convertValue(deviceItemData.get(DeviceListItemFields.F_PORT), Long.class));
                        deviceData.setAppKey((String) deviceItemData.get(DeviceListItemFields.APP_KEY));
                        deviceData.setFrameCounterValidation(!(Boolean) deviceItemData.get(DeviceListItemFields.SKIP_F_CNT_CHECK));
                        response.setDeviceData(deviceData);
                        return response;
                    }, taskExecutor)).toList();
            List<UpdateGatewayDeviceResponse> responseList = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v ->
                    futures.stream()
                            .map(CompletableFuture::join)
                            .filter(response -> StringUtils.hasText(response.getDeviceName()))
                            .toList()).join();
            deviceItemList.addAll(responseList);
            offset = end;
        }

        // save devices
        deviceItemList.forEach(deviceItem -> {
            GatewayDeviceData deviceData = deviceItem.getDeviceData();
            DeviceModelIdentifier deviceModelIdentifier = DeviceModelIdentifier.of(deviceData.getDeviceModel());
            // save device
            deviceService.manageGatewayDevices(deviceData.getGatewayEUI(), deviceData.getEui(), GatewayDeviceOperation.ADD);
            AtomicReference<String> timeoutEntityKey = new AtomicReference<>();
            BiFunction<Device, Map<String, Object>, Boolean> beforeSaveDevice = (device, metadata) -> {
                List<Entity> entities = new ArrayList<>(device.getEntities());
                Entity timeoutEntity = deviceService.generateOfflineTimeoutEntity(device.getKey());
                entities.add(timeoutEntity);
                timeoutEntityKey.set(timeoutEntity.getKey());
                device.setEntities(entities);
                device.setAdditional(json.convertValue(deviceData, new TypeReference<>() {}));
                return true;
            };

            // Custom models have no blueprint behind them, so they are created by template id.
            if (CustomDeviceModelService.isCustomModel(deviceModelIdentifier.getVendorId())) {
                DeviceTemplate deviceTemplate = customDeviceModelService.getByIdentifier(deviceModelIdentifier.getModelId());
                if (deviceTemplate == null) {
                    throw ServiceException.with(ErrorCode.PARAMETER_VALIDATION_FAILED.getErrorCode(),
                            "Custom device model not found: " + deviceModelIdentifier.getModelId()).build();
                }

                deviceTemplateParserProvider.createDevice(
                        Constants.INTEGRATION_ID,
                        deviceTemplate.getId(),
                        GatewayString.standardizeEUI(deviceData.getEui()),
                        deviceItem.getDeviceName(),
                        beforeSaveDevice);
            } else {
                deviceTemplateParserProvider.createDevice(
                        Constants.INTEGRATION_ID,
                        deviceModelIdentifier.getVendorId(),
                        deviceModelIdentifier.getModelId(),
                        GatewayString.standardizeEUI(deviceData.getEui()),
                        deviceItem.getDeviceName(),
                        beforeSaveDevice);
            }
            long offlineTimeout = offlineTimeoutsByEui.getOrDefault(deviceData.getEui(), Constants.DEFAULT_DEVICE_OFFLINE_TIMEOUT);
            entityValueServiceProvider.saveLatestValues(ExchangePayload.create(Map.of(
                    timeoutEntityKey.get(), offlineTimeout
            )));
        });
    }
}
