package com.milesight.beaveriot.integrations.llm;

import com.milesight.beaveriot.context.integration.bootstrap.IntegrationBootstrap;
import com.milesight.beaveriot.context.integration.model.Integration;
import com.milesight.beaveriot.context.security.TenantContext;
import com.milesight.beaveriot.integrations.llm.process.LocalOllamaProcessManager;
import com.milesight.beaveriot.integrations.llm.service.LlmApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmBootstrap implements IntegrationBootstrap {

    /**
     * IntegrationBootstrapManager invokes onStarted() outside of any HTTP request, so
     * TenantContext (normally populated per-request from the auth token) is never set here.
     * This platform is single-tenant, so "default" is always correct - without this,
     * any entityValueServiceProvider call made during boot (e.g. LlmApiService.init())
     * throws "TenantContext is not set" and silently fails, leaving the LLM client
     * uninitialized until the user happens to re-save the Settings page.
     */
    private static final String BOOTSTRAP_TENANT_ID = "default";

    @Autowired
    private LlmApiService llmApiService;

    @Autowired
    private LocalOllamaProcessManager localOllamaProcessManager;

    @Override
    public void onPrepared(Integration integration) {
        // do nothing
    }

    @Override
    public void onStarted(Integration integrationConfig) {
        log.info("LLM integration starting");
        TenantContext.setTenantId(BOOTSTRAP_TENANT_ID);
        try {
            localOllamaProcessManager.startIfAvailable();
            llmApiService.init();
        } finally {
            TenantContext.clear();
        }
        log.info("LLM integration started");
    }

    @Override
    public void onDestroy(Integration integration) {
        // do nothing
    }
}
