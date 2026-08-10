package com.milesight.beaveriot.integrations.irivioc;

import com.milesight.beaveriot.context.api.DeviceStatusServiceProvider;
import com.milesight.beaveriot.context.integration.bootstrap.IntegrationBootstrap;
import com.milesight.beaveriot.context.integration.model.DeviceStatusConfig;
import com.milesight.beaveriot.context.integration.model.Integration;
import com.milesight.beaveriot.integrations.irivioc.service.IrivMqttService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class IrivIocGatewayBootstrap implements IntegrationBootstrap {
    private final IrivMqttService irivMqttService;
    private final DeviceStatusServiceProvider deviceStatusServiceProvider;

    public IrivIocGatewayBootstrap(IrivMqttService irivMqttService, DeviceStatusServiceProvider deviceStatusServiceProvider) {
        this.irivMqttService = irivMqttService;
        this.deviceStatusServiceProvider = deviceStatusServiceProvider;
    }

    @Override
    public void onPrepared(Integration integrationConfig) {
        // do nothing
    }

    @Override
    public void onStarted(Integration integrationConfig) {
        log.info("IRIV-IOC MQTT Gateway integration starting");
        irivMqttService.subscribe();
        log.info("IRIV-IOC MQTT Gateway integration started");
    }

    @Override
    public void onDestroy(Integration integrationConfig) {
        log.info("IRIV-IOC MQTT Gateway integration destroying");
        irivMqttService.unsubscribe();
    }

    @Override
    public void onEnabled(String tenantId, Integration integrationConfig) {
        // the gateway has no LWT/availability topic - infer offline after a few missed publish cycles
        DeviceStatusConfig config = DeviceStatusConfig.builder()
                .offlineTimeoutFetcher(device -> Duration.ofSeconds(Constants.DEFAULT_OFFLINE_TIMEOUT_SECONDS))
                .build();
        deviceStatusServiceProvider.register(integrationConfig.getId(), config);
        IntegrationBootstrap.super.onEnabled(tenantId, integrationConfig);
    }
}
