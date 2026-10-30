package com.shreyasnandurkar.botvinnikapi.api.openai;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns normalized ChatChunks into the Responses event stream: response.created, then
 * output_item.added / deltas / output_item.done per item, then response.completed with the full output.
 */
@Component
public class ResponsesSseEncoder {

    private static final Logger log = LoggerFactory.getLogger(ResponsesSseEncoder.class);

    private final ObjectMapper mapper;

    public ResponsesSseEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Flux<ServerSentEvent<String>> encode(Flux<ChatChunk> chunks, String model) {
        return Flux.defer(() -> {
            EgressState state = new EgressState(ResponsesDtos.id("resp_"), model, Instant.now().getEpochSecond());
            return chunks
                    .concatMap(chunk -> Flux.fromIterable(events(chunk, state)))
                    // §10 point of no return: before response.created the error can still become a
                    // JSON error or a failover; after it, only an in-band response.failed is honest.
                    .onErrorResume(e -> {
                        if (!state.started) {
                            return Flux.error(e);
                        }
                        state.finished = true;
                        log.warn("Stream for model '{}' died after events were flushed; emitting response.failed: {}",
                                model, e.toString());
                        return Flux.just(failedEvent(state, e));
                    })
                    // Streams that end without a finish chunk still need a terminal response event.
                    .concatWith(Flux.defer(() -> Flux.fromIterable(finish(state, null, null))));
        });
    }

    /** The open item and everything finished so far, which response.completed repeats in full. */
    private static final class EgressState {
        final String id;
        final String model;
        final long createdAt;
        final List<Object> output = new ArrayList<>();
        final StringBuilder buffer = new StringBuilder();
        int sequence;
        boolean started;
        boolean finished;
        String openKind;
        String openItemId;
        int openOutputIndex;
        int openToolIndex = -1;
        String openCallId;
        String openName;

        EgressState(String id, String model, long createdAt) {
            this.id = id;
            this.model = model;
            this.createdAt = createdAt;
        }

        int next() {
            return sequence++;
        }
    }

    private List<ServerSentEvent<String>> events(ChatChunk chunk, EgressState state) {
        List<ServerSentEvent<String>> out = new ArrayList<>(4);
        start(state, out);
        if (chunk.reasoningDelta() != null) {
            if (!"reasoning".equals(state.openKind)) {
                open(state, "reasoning", "rs_", out);
                out.add(event(state, ResponsesDtos.ReasoningItem.of(state.openItemId, List.of()), "added"));
                out.add(event("response.reasoning_summary_part.added", new ResponsesDtos.SummaryPartEvent(
                        "response.reasoning_summary_part.added", state.next(), state.openItemId,
                        state.openOutputIndex, 0, ResponsesDtos.SummaryText.of(""))));
            }
            state.buffer.append(chunk.reasoningDelta());
            out.add(event("response.reasoning_summary_text.delta", new ResponsesDtos.SummaryTextEvent(
                    "response.reasoning_summary_text.delta", state.next(), state.openItemId,
                    state.openOutputIndex, 0, chunk.reasoningDelta(), null)));
        }
        if (chunk.contentDelta() != null) {
            if (!"message".equals(state.openKind)) {
                open(state, "message", "msg_", out);
                out.add(event(state, ResponsesDtos.MessageItem.of(state.openItemId, "in_progress", List.of()), "added"));
                out.add(event("response.content_part.added", new ResponsesDtos.ContentPartEvent(
                        "response.content_part.added", state.next(), state.openItemId,
                        state.openOutputIndex, 0, ResponsesDtos.OutputText.of(""))));
            }
            state.buffer.append(chunk.contentDelta());
            out.add(event("response.output_text.delta", new ResponsesDtos.TextEvent(
                    "response.output_text.delta", state.next(), state.openItemId,
                    state.openOutputIndex, 0, chunk.contentDelta(), null, List.of())));
        }
        if (chunk.toolCallDelta() != null) {
            ToolCallDelta d = chunk.toolCallDelta();
            boolean sameTool = "function_call".equals(state.openKind) && state.openToolIndex == d.index();
            if (d.id() != null || !sameTool) {
                open(state, "function_call", "fc_", out);
                state.openToolIndex = d.index();
                state.openCallId = d.id() == null ? ToolCall.generatedId() : d.id();
                state.openName = d.name() == null ? "" : d.name();
                out.add(event(state, ResponsesDtos.FunctionCallItem.of(state.openItemId, state.openCallId,
                        state.openName, "", "in_progress"), "added"));
            }
            if (d.argumentsFragment() != null && !d.argumentsFragment().isEmpty()) {
                state.buffer.append(d.argumentsFragment());
                out.add(event("response.function_call_arguments.delta", new ResponsesDtos.FunctionArgsEvent(
                        "response.function_call_arguments.delta", state.next(), state.openItemId,
                        state.openOutputIndex, d.argumentsFragment(), null)));
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
        ResponsesDtos.Response response = ResponsesDtos.Response.of(state.id, state.createdAt, state.model,
                "in_progress", null, null, List.of(), null);
        out.add(event("response.created",
                new ResponsesDtos.ResponseEvent("response.created", state.next(), response)));
        out.add(event("response.in_progress",
                new ResponsesDtos.ResponseEvent("response.in_progress", state.next(), response)));
    }

    /** Closes whatever item is open, then makes a new one current at the next output index. */
    private void open(EgressState state, String kind, String idPrefix, List<ServerSentEvent<String>> out) {
        close(state, out);
        state.openKind = kind;
        state.openItemId = ResponsesDtos.id(idPrefix);
        state.openOutputIndex = state.output.size();
    }

    /** Emits the item's done events and records the finished item for response.completed. */
    private void close(EgressState state, List<ServerSentEvent<String>> out) {
        if (state.openKind == null) {
            return;
        }
        String text = state.buffer.toString();
        Object item = switch (state.openKind) {
            case "reasoning" -> {
                ResponsesDtos.SummaryText part = ResponsesDtos.SummaryText.of(text);
                out.add(event("response.reasoning_summary_text.done", new ResponsesDtos.SummaryTextEvent(
                        "response.reasoning_summary_text.done", state.next(), state.openItemId,
                        state.openOutputIndex, 0, null, text)));
                out.add(event("response.reasoning_summary_part.done", new ResponsesDtos.SummaryPartEvent(
                        "response.reasoning_summary_part.done", state.next(), state.openItemId,
                        state.openOutputIndex, 0, part)));
                yield ResponsesDtos.ReasoningItem.of(state.openItemId, List.of(part));
            }
            case "message" -> {
                ResponsesDtos.OutputText part = ResponsesDtos.OutputText.of(text);
                out.add(event("response.output_text.done", new ResponsesDtos.TextEvent(
                        "response.output_text.done", state.next(), state.openItemId,
                        state.openOutputIndex, 0, null, text, List.of())));
                out.add(event("response.content_part.done", new ResponsesDtos.ContentPartEvent(
                        "response.content_part.done", state.next(), state.openItemId,
                        state.openOutputIndex, 0, part)));
                yield ResponsesDtos.MessageItem.of(state.openItemId, "completed", List.of(part));
            }
            default -> {
                out.add(event("response.function_call_arguments.done", new ResponsesDtos.FunctionArgsEvent(
                        "response.function_call_arguments.done", state.next(), state.openItemId,
                        state.openOutputIndex, null, text)));
                yield ResponsesDtos.FunctionCallItem.of(state.openItemId, state.openCallId, state.openName,
                        text, "completed");
            }
        };
        out.add(event(state, item, "done"));
        state.output.add(item);
        state.buffer.setLength(0);
        state.openKind = null;
        state.openToolIndex = -1;
    }

    /** Usage and the complete output ride on the terminal event; SDKs read the final response from it. */
    private List<ServerSentEvent<String>> finish(EgressState state, String finishReason, TokenUsage usage) {
        if (state.finished) {
            return List.of();
        }
        state.finished = true;
        List<ServerSentEvent<String>> out = new ArrayList<>(6);
        start(state, out);
        close(state, out);
        String status = ResponsesProtocol.status(finishReason);
        String type = "completed".equals(status) ? "response.completed" : "response.incomplete";
        out.add(event(type, new ResponsesDtos.ResponseEvent(type, state.next(),
                ResponsesDtos.Response.of(state.id, state.createdAt, state.model, status,
                        ResponsesProtocol.incompleteDetails(finishReason), null,
                        List.copyOf(state.output), ResponsesDtos.Usage.of(usage)))));
        return out;
    }

    private ServerSentEvent<String> failedEvent(EgressState state, Throwable e) {
        String message = e instanceof GatewayException ? e.getMessage()
                : "The provider connection was interrupted mid-stream.";
        String code = e instanceof GatewayException ge && ge.code() != null ? ge.code() : "server_error";
        return event("response.failed", new ResponsesDtos.ResponseEvent("response.failed", state.next(),
                ResponsesDtos.Response.of(state.id, state.createdAt, state.model, "failed", null,
                        new ResponsesDtos.ErrorObj(code, message), List.copyOf(state.output), null)));
    }

    private ServerSentEvent<String> event(EgressState state, Object item, String phase) {
        String type = "response.output_item." + phase;
        return event(type, new ResponsesDtos.OutputItemEvent(type, state.next(), state.openOutputIndex, item));
    }

    private ServerSentEvent<String> event(String type, Object payload) {
        return ServerSentEvent.builder(mapper.writeValueAsString(payload)).event(type).build();
    }
}
