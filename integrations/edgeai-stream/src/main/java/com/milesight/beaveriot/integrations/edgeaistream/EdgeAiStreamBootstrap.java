package com.milesight.beaveriot.integrations.edgeaistream;

import com.milesight.beaveriot.context.integration.bootstrap.IntegrationBootstrap;
import com.milesight.beaveriot.context.integration.model.Integration;
import org.springframework.stereotype.Component;

/**
 * Lifecycle entry point for the EdgeAI camera stream integration.
 * <p>
 * There is nothing to start or tear down: this integration owns no devices and polls
 * nothing. It exists so the module is registered and its controllers are mounted - the
 * work happens per request, when a browser opens a stream.
 *
 * @author cytron
 */
@Component
public class EdgeAiStreamBootstrap implements IntegrationBootstrap {
    @Override
    public void onPrepared(Integration integration) {
        // no preparation required
    }

    @Override
    public void onStarted(Integration integrationConfig) {
        // no background work to start
    }

    @Override
    public void onDestroy(Integration integration) {
        // nothing to release
    }
}
