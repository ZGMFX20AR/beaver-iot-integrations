package com.milesight.beaveriot.integrations.milesightgateway;

import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.integrations.milesightgateway.model.request.CustomDeviceModelRequest;
import com.milesight.beaveriot.integrations.milesightgateway.model.response.CustomDeviceModelResponse;
import com.milesight.beaveriot.integrations.milesightgateway.service.CustomDeviceModelService;
import com.milesight.beaveriot.integrations.milesightgateway.service.DeviceModelService;
import com.milesight.beaveriot.integrations.milesightgateway.util.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manages custom LoRaWAN device models, letting devices that are not published in the
 * blueprint library be onboarded through the gateway.
 *
 * @author cytron
 */
@RestController
@RequestMapping("/" + Constants.INTEGRATION_ID + "/custom-device-models")
public class MilesightGatewayCustomModelController {
    @Autowired
    CustomDeviceModelService customDeviceModelService;

    @Autowired
    DeviceModelService deviceModelService;

    @GetMapping
    public ResponseBody<List<CustomDeviceModelResponse>> listCustomModels() {
        List<CustomDeviceModelResponse> models = customDeviceModelService.listCustomModels().stream()
                .map(CustomDeviceModelResponse::of)
                .toList();
        return ResponseBuilder.success(models);
    }

    @GetMapping("/{identifier}")
    public ResponseBody<CustomDeviceModelResponse> getCustomModel(@PathVariable("identifier") String identifier) {
        DeviceTemplate deviceTemplate = customDeviceModelService.getByIdentifier(identifier);
        return ResponseBuilder.success(deviceTemplate == null ? null : CustomDeviceModelResponse.of(deviceTemplate));
    }

    @PostMapping
    public ResponseBody<CustomDeviceModelResponse> createCustomModel(@RequestBody CustomDeviceModelRequest request) {
        DeviceTemplate deviceTemplate = customDeviceModelService.createCustomModel(request);
        // Refresh the picker so the new model is immediately selectable when adding a device.
        deviceModelService.syncDeviceModelListToAdd();
        return ResponseBuilder.success(CustomDeviceModelResponse.of(deviceTemplate));
    }

    @PutMapping("/{identifier}")
    public ResponseBody<CustomDeviceModelResponse> updateCustomModel(@PathVariable("identifier") String identifier,
                                                                     @RequestBody CustomDeviceModelRequest request) {
        DeviceTemplate deviceTemplate = customDeviceModelService.updateCustomModel(identifier, request);
        deviceModelService.syncDeviceModelListToAdd();
        return ResponseBuilder.success(CustomDeviceModelResponse.of(deviceTemplate));
    }

    @DeleteMapping("/{identifier}")
    public ResponseBody<Void> deleteCustomModel(@PathVariable("identifier") String identifier) {
        customDeviceModelService.deleteCustomModel(identifier);
        deviceModelService.syncDeviceModelListToAdd();
        return ResponseBuilder.success();
    }
}
