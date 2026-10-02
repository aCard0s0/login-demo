package com.demo.agentservice.run;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.Agent;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.token.Caller;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One run of one agent: the model is asked, every tool it calls is checked against the owner's permissions as
 * they are at that moment, and the answer goes back, until the model stops or the turn limit is reached.
 * Synchronous: the user's browser waits for the reply.
 *
 * <p>The permission checks live in {@link McpTools} and {@link AgentTools}; this class only routes a call to
 * the right one and turns a refusal into an error result the model can read and a line the owner can see.
 */
@Service
public class AgentRunner {

    private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<>() {};

    private final AgentService agents;
    private final ActivityLog activity;
    private final Model model;
    private final String modelId;
    private final int maxTurns;

    public AgentRunner(AgentService agents, ActivityLog activity, Model model,
                       @Value("${anthropic.model}") String modelId, @Value("${agents.max-turns}") int maxTurns) {
        this.agents = agents;
        this.activity = activity;
        this.model = model;
        this.modelId = modelId;
        this.maxTurns = maxTurns;
    }

    /** Runs the caller's agent on a prompt. {@code bearer} is the caller's own token, forwarded to servers that ask for it. */
    public RunResponse run(Caller caller, String bearer, Long agentId, String prompt) {
        if (model instanceof AnthropicModel real && !real.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ANTHROPIC_API_KEY is not configured");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }
        Agent agent = agents.get(caller, agentId);
        activity.record(agent.getId(), Activity.RUN_STARTED, "prompt: " + ActivityLog.brief(prompt));

        try (McpTools mcp = McpTools.connect(agent, bearer, activity, () -> agents.get(caller, agentId))) {
            AgentTools builtins = new AgentTools(caller, agent, agents, activity);
            List<Tool> tools = Stream.concat(builtins.defs(agent.getOthersAccess()).stream(), mcp.defs().stream()).toList();

            List<MessageParam> history = new ArrayList<>();
            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(prompt).build());
            String reply = "";
            int turns = 0;
            while (turns < maxTurns) {
                turns++;
                MessageCreateParams.Builder request = MessageCreateParams.builder()
                        .model(modelId)
                        .maxTokens(4096L)
                        .messages(history);
                if (!agent.getInstructions().isBlank()) {
                    request.system(agent.getInstructions());
                }
                tools.forEach(request::addTool);
                Message answer = model.create(request.build());

                history.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(answer.content().stream().map(ContentBlock::toParam).toList()).build());
                String text = answer.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text)
                        .collect(Collectors.joining("\n")).strip();
                if (!text.isEmpty()) {
                    reply = text;
                }
                List<ToolUseBlock> calls = answer.content().stream().flatMap(b -> b.toolUse().stream()).toList();
                // Anything but a tool call ends the run: end_turn, max_tokens and refusal alike.
                if (!answer.stopReason().map(StopReason.TOOL_USE::equals).orElse(false) || calls.isEmpty()) {
                    break;
                }
                // Every result in one message, however many tools the model called at once.
                history.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .contentOfBlockParams(calls.stream().map(call -> result(agent, call, builtins, mcp)).toList()).build());
            }
            activity.record(agent.getId(), Activity.RUN_FINISHED, turns + " turns; reply: " + ActivityLog.brief(reply));
            return new RunResponse(reply, turns);
        } catch (RuntimeException e) {
            activity.record(agent.getId(), Activity.RUN_FINISHED, "failed: " + ActivityLog.brief(e.getMessage()));
            throw e;
        }
    }

    /** Runs one tool call -- or refuses it -- and says what happened in the agent's log either way. */
    private ContentBlockParam result(Agent agent, ToolUseBlock call, AgentTools builtins, McpTools mcp) {
        Map<String, Object> args;
        try {
            args = call._input().convert(ARGS);
        } catch (RuntimeException e) {
            args = Map.of();
        }
        String what = call.name() + " " + args;
        ToolResult result;
        try {
            if (builtins.has(call.name())) {
                result = builtins.call(call.name(), args);
            } else if (mcp.has(call.name())) {
                result = mcp.call(call.name(), args);
            } else {
                throw new AccessDenied("no such tool: " + call.name());
            }
            activity.record(agent.getId(), Activity.TOOL_CALL,
                    what + (result.error() ? " -> error: " : " -> ok: ") + ActivityLog.brief(result.text()));
        } catch (AccessDenied denied) {
            activity.record(agent.getId(), Activity.TOOL_DENIED, call.name() + ": " + denied.getMessage());
            result = ToolResult.error("denied: " + denied.getMessage());
        } catch (RuntimeException e) {
            activity.record(agent.getId(), Activity.TOOL_CALL, what + " -> error: " + ActivityLog.brief(e.getMessage()));
            result = ToolResult.error("error: " + e.getMessage());
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(call.id())
                .content(result.text().isEmpty() ? "(empty)" : result.text())
                .isError(result.error())
                .build());
    }
}
