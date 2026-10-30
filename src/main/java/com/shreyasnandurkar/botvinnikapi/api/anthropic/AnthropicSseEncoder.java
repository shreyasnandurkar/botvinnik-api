package com.shreyasnandurkar.botvinnikapi.api.anthropic;

import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns normalized ChatChunks into Anthropic's named SSE event sequence:
 * message_start, then content_block_start/delta/stop per block, message_delta, message_stop.
 */
@Component
public class AnthropicSseEncoder {

    private static final Logger log = LoggerFactory.getLogger(AnthropicSseEncoder.class);

    private final ObjectMapper mapper;

    public AnthropicSseEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Flux<ServerSentEvent<String>> encode(Flux<ChatChunk> chunks, String model) {
        return Flux.defer(() -> {
            EgressState state = new EgressState(AnthropicDtos.messageId(), model);
            return chunks
                    .concatMap(chunk -> Flux.fromIterable(events(chunk, state)))
                    // §10 point of no return: before message_start the error can still become a
                    // JSON error or a failover; after it, only an in-band error event is honest.
                    .onErrorResume(e -> {
                        if (!state.started) {
                            return Flux.error(e);
                        }
                        // An error event ends the stream; no message_stop follows it.
                        state.finished = true;
                        log.warn("Stream for model '{}' died after events were flushed; emitting error event: {}",
                                model, e.toString());
                        return Flux.just(errorEvent(e));
                    })
                    // Streams that end without a finish chunk still need a well-formed ending.
                    .concatWith(Flux.defer(() -> Flux.fromIterable(finish(state, null, null))));
        });
    }

    /** Which block is open, so every content_block_start gets its matching stop. */
    private static final class EgressState {
        final String id;
        final String model;
        boolean started;
        boolean finished;
        int nextIndex;
        int openIndex = -1;
        String openKind;
        int openToolIndex = -1;

        EgressState(String id, String model) {
            this.id = id;
            this.model = model;
        }
    }

    private List<ServerSentEvent<String>> events(ChatChunk chunk, EgressState state) {
        List<ServerSentEvent<String>> out = new ArrayList<>(3);
        start(state, out);
        if (chunk.reasoningDelta() != null) {
            openBlock(state, "thinking", AnthropicDtos.ContentBlock.thinking(""), out);
            out.add(delta(state, new AnthropicDtos.Delta("thinking_delta", null, chunk.reasoningDelta(), null)));
        }
        if (chunk.contentDelta() != null) {
            openBlock(state, "text", AnthropicDtos.ContentBlock.text(""), out);
            out.add(delta(state, new AnthropicDtos.Delta("text_delta", chunk.contentDelta(), null, null)));
        }
        if (chunk.toolCallDelta() != null) {
            ToolCallDelta d = chunk.toolCallDelta();
            boolean sameTool = "tool_use".equals(state.openKind) && state.openToolIndex == d.index();
            if (d.id() != null || !sameTool) {
                closeBlock(state, out);
                openBlock(state, "tool_use", AnthropicDtos.ContentBlock.toolUse(
                        d.id() == null ? ToolCall.generatedId() : d.id(),
                        d.name() == null ? "" : d.name(), mapper.createObjectNode()), out);
                state.openToolIndex = d.index();
            }
            if (d.argumentsFragment() != null && !d.argumentsFragment().isEmpty()) {
                out.add(delta(state, new AnthropicDtos.Delta("input_json_delta", null, null, d.argumentsFragment())));
            }
        }
        if (chunk.finishReason() != null) {
            out.addAll(finish(state, chunk.finishReason(), chunk.usage()));
        }
        return out;
    }

    private void start(EgressState state, List<ServerSentEvent<String>> out) {
        if (state.started) {
            return;
        }
        state.started = true;
        out.add(event("message_start", new AnthropicDtos.MessageStart("message_start",
                new AnthropicDtos.MessageResponse(state.id, "message", "assistant", state.model, List.of(),
                        null, null, new AnthropicDtos.Usage(0, 0)))));
    }

    /** Opens a block of this kind unless one is already open; tool blocks always open fresh. */
    private void openBlock(EgressState state, String kind, AnthropicDtos.ContentBlock block,
                           List<ServerSentEvent<String>> out) {
        if (kind.equals(state.openKind) && !"tool_use".equals(kind)) {
            return;
        }
        closeBlock(state, out);
        state.openIndex = state.nextIndex++;
        state.openKind = kind;
        out.add(event("content_block_start",
                new AnthropicDtos.ContentBlockStart("content_block_start", state.openIndex, block)));
    }

    private void closeBlock(EgressState state, List<ServerSentEvent<String>> out) {
        if (state.openKind == null) {
            return;
        }
        out.add(event("content_block_stop", new AnthropicDtos.ContentBlockStop("content_block_stop", state.openIndex)));
        state.openKind = null;
        state.openToolIndex = -1;
    }

    private ServerSentEvent<String> delta(EgressState state, AnthropicDtos.Delta delta) {
        return event("content_block_delta",
                new AnthropicDtos.ContentBlockDelta("content_block_delta", state.openIndex, delta));
    }

    /** Usage rides on message_delta; Anthropic SDKs merge it into the final message. */
    private List<ServerSentEvent<String>> finish(EgressState state, String finishReason, TokenUsage usage) {
        if (state.finished) {
            return List.of();
        }
        state.finished = true;
        List<ServerSentEvent<String>> out = new ArrayList<>(4);
        start(state, out);
        closeBlock(state, out);
        out.add(event("message_delta", new AnthropicDtos.MessageDelta("message_delta",
                new AnthropicDtos.MessageDeltaBody(AnthropicProtocol.stopReason(finishReason), null),
                AnthropicDtos.Usage.of(usage))));
        out.add(event("message_stop", new AnthropicDtos.MessageStop("message_stop")));
        return out;
    }

    private ServerSentEvent<String> errorEvent(Throwable e) {
        String message = e instanceof GatewayException ? e.getMessage()
                : "The provider connection was interrupted mid-stream.";
        int status = e instanceof GatewayException ge ? ge.status().value() : 500;
        return event("error", new AnthropicDtos.ErrorBody("error",
                new AnthropicDtos.ErrorDetail(AnthropicProtocol.errorType(status), message)));
    }

    private ServerSentEvent<String> event(String name, Object payload) {
        return ServerSentEvent.builder(mapper.writeValueAsString(payload)).event(name).build();
    }
}
