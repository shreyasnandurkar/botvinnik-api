package com.shreyasnandurkar.botvinnikapi.api.anthropic;

import com.shreyasnandurkar.botvinnikapi.api.ClientProtocol;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Anthropic Messages, spoken by Claude Code and the Anthropic SDKs. */
@Component
public class AnthropicProtocol implements ClientProtocol<AnthropicMessagesRequest> {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProtocol.class);

    static final String MESSAGES_PATH = "/v1/messages";

    private final AnthropicSseEncoder sseEncoder;
    private final ObjectMapper mapper;

    public AnthropicProtocol(AnthropicSseEncoder sseEncoder, ObjectMapper mapper) {
        this.sseEncoder = sseEncoder;
        this.mapper = mapper;
    }

    @Override
    public List<String> paths() {
        return List.of(MESSAGES_PATH);
    }

    @Override
    public InboundChat toChatRequest(AnthropicMessagesRequest body) {
        body.validate();
        return new InboundChat(body.model, Boolean.TRUE.equals(body.stream), body.promptChars(),
                body::toChatRequest);
    }

    @Override
    public Object encodeResponse(ChatResponse resp) {
        List<AnthropicDtos.ContentBlock> content = new ArrayList<>();
        if (resp.reasoningContent() != null) {
            content.add(AnthropicDtos.ContentBlock.thinking(resp.reasoningContent()));
        }
        if (resp.content() != null && !resp.content().isEmpty()) {
            content.add(AnthropicDtos.ContentBlock.text(resp.content()));
        }
        if (resp.toolCalls() != null) {
            for (ToolCall tc : resp.toolCalls()) {
                content.add(AnthropicDtos.ContentBlock.toolUse(tc.id(), tc.name(), parseInput(tc.argumentsJson())));
            }
        }
        return new AnthropicDtos.MessageResponse(AnthropicDtos.messageId(), "message", "assistant",
                resp.model(), content, stopReason(resp.finishReason()), null,
                AnthropicDtos.Usage.of(resp.usage()));
    }

    @Override
    public Flux<?> encodeStream(Flux<ChatChunk> chunks, AnthropicMessagesRequest body, String model) {
        return sseEncoder.encode(chunks, model);
    }

    @Override
    public Object encodeError(int status, String message, String type, String param, String code) {
        return new AnthropicDtos.ErrorBody("error", new AnthropicDtos.ErrorDetail(errorType(status), message));
    }

    /** Anthropic's input is a JSON object; a backend's malformed arguments become an empty one. */
    private JsonNode parseInput(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            log.warn("Tool arguments are not valid JSON, returning {{}}: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** OpenAI vocabulary (the gateway's common one) back to Anthropic's. */
    static String stopReason(String finishReason) {
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    /** Anthropic types errors by HTTP status; overloaded_error makes its SDKs retry. */
    static String errorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 503, 529 -> "overloaded_error";
            default -> "api_error";
        };
    }
}
