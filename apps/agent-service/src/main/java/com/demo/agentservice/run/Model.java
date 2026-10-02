package com.demo.agentservice.run;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/** One call to the model. An interface so the tests can script the model's answers; {@link AnthropicModel} is the real one. */
@FunctionalInterface
public interface Model {

    Message create(MessageCreateParams params);
}
