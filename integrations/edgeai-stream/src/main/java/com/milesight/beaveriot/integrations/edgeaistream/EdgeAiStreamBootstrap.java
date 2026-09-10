package com.milesight.beaveriot.integrations.edgeaistream;

import com.milesight.beaveriot.context.integration.bootstrap.IntegrationBootstrap;
import com.milesight.beaveriot.context.integration.model.Integration;
import com.milesight.beaveriot.context.security.TenantContext;
import com.milesight.beaveriot.integrations.edgeaistream.service.StreamSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lifecycle entry point for the EdgeAI camera stream integration.
 * <p>
 * This integration owns no devices and polls nothing; its only startup job is to prime the
 * relay's source cache, so that the first viewer after a restart is served without the
 * stream request having to touch the database.
 *
 * @author cytron
 */
@Component
@Slf4j
public class EdgeAiStreamBootstrap implements IntegrationBootstrap {
    @Autowired
    StreamSourceService streamSourceService;

    @Value("${edgeai-stream.default-tenant:default}")
    String defaultTenant;

    @Override
    public void onPrepared(Integration integration) {
        // no preparation required
    }

    @Override
    public void onStarted(Integration integrationConfig) {
        // Loading here rather than lazily on first use is the point: a lazy load would run
        // on a stream request, and a database query on a stream request holds its
        // connection for as long as the viewer watches.
        try {
            TenantContext.setTenantId(defaultTenant);
            streamSourceService.refreshCache(defaultTenant);
        } catch (Exception e) {
            // Never block startup over this. A source added later refreshes the cache on
            // save, so the only cost is that streams do not work until something writes.
            log.warn("Could not prime the camera source cache for tenant '{}': {}",
                    defaultTenant, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void onDestroy(Integration integration) {
        // nothing to release
    }
}
