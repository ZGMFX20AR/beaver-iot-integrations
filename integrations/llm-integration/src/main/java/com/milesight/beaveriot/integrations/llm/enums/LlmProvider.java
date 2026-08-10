package com.milesight.beaveriot.integrations.llm.enums;

import com.milesight.beaveriot.base.enums.EnumCode;

/**
 * Which LLM backend the integration is configured to call.
 */
public enum LlmProvider implements EnumCode {
    // NOTE: codes are intentionally lowercase. The web UI runs every integration-detail
    // response through objectToCamelCase(), which lower-cases the first letter of any
    // enum key (e.g. "OPENROUTER" -> "oPENROUTER"), so an uppercase code would no longer
    // match this map on save and fail enum validation. Lowercase single-word codes are a
    // fixed point of that transform, matching how other integrations define enum codes.
    OLLAMA("ollama", "Ollama"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic (Claude)"),
    OPENROUTER("openrouter", "OpenRouter"),
    ;

    private final String code;
    private final String value;

    LlmProvider(String code, String value) {
        this.code = code;
        this.value = value;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getValue() {
        return value;
    }
}
