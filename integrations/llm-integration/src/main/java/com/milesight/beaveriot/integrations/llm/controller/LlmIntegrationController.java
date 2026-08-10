package com.milesight.beaveriot.integrations.llm.controller;

import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.integrations.llm.constant.LlmIntegrationConstants;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/" + LlmIntegrationConstants.INTEGRATION_IDENTIFIER)
public class LlmIntegrationController {

    private static final String MODELS_KEY = LlmIntegrationConstants.INTEGRATION_IDENTIFIER + ".integration.models";

    private final EntityValueServiceProvider entityValueServiceProvider;

    public LlmIntegrationController(EntityValueServiceProvider entityValueServiceProvider) {
        this.entityValueServiceProvider = entityValueServiceProvider;
    }

    @GetMapping("/models")
    public ResponseBody<List<String>> getModels() {
        Object value = entityValueServiceProvider.findValueByKey(MODELS_KEY);
        if (value == null || !StringUtils.hasText(value.toString())) {
            return ResponseBuilder.success(List.of());
        }

        List<String> models = Arrays.stream(value.toString().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        return ResponseBuilder.success(models);
    }

}
