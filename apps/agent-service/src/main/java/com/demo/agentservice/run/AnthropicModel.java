package com.demo.agentservice.run;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Claude, through the official SDK. The client is built on first use, so a deployment with no key still
 * starts: every page works, and only Run answers 503 until {@code ANTHROPIC_API_KEY} is set.
 */
@Component
public class AnthropicModel implements Model {

    private final String apiKey;

    private volatile AnthropicClient client;

    public AnthropicModel(@Value("${anthropic.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public Message create(MessageCreateParams params) {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ANTHROPIC_API_KEY is not configured");
        }
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(60)).build();
                }
            }
        }
        return client.messages().create(params);
    }
}
