package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns normalized ChatChunks into Ollama's NDJSON stream: one JSON object per line,
 * the last one with done: true. Emits objects, not Strings: for application/x-ndjson Spring's
 * JSON encoder writes and flushes one line per element, but would quote a pre-serialized String.
 */
@Component
public class OllamaNdjsonEncoder {

    private static final Logger log = LoggerFactory.getLogger(OllamaNdjsonEncoder.class);

    private final ObjectMapper mapper;

    public OllamaNdjsonEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @param generate /api/generate lines (response field) instead of /api/chat lines (message field) */
    public Flux<Object> encode(Flux<ChatChunk> chunks, String model, boolean generate) {
        return Flux.defer(() -> {
            EgressState state = new EgressState(model, generate);
            return chunks
                    .concatMap(chunk -> Flux.fromIterable(lines(chunk, state)))
                    // §10 point of no return: before the first line the error can still become a
                    // JSON error or a failover; after it, only an in-band error line is honest.
                    .onErrorResume(e -> {
                        if (!state.started) {
                            return Flux.error(e);
                        }
                        state.finished = true;
                        log.warn("Stream for model '{}' died after lines were flushed; emitting error line: {}",
                                model, e.toString());
                        String message = e instanceof GatewayException ? e.getMessage()
                                : "The provider connection was interrupted mid-stream.";
                        return Flux.just(new OllamaDtos.ErrorBody(message));
                    })
                    // Streams that end without a finish chunk still need a done line.
                    .concatWith(Flux.defer(() -> Flux.fromIterable(finish(state, null, null))));
        });
    }

    /**
     * Ollama emits each tool call whole, but backends stream arguments in fragments,
     * so calls are assembled here and sent together just before the done line.
     */
    private static final class EgressState {
        final String model;
        final boolean generate;
        final List<PendingCall> calls = new ArrayList<>();
        boolean started;
        boolean finished;

        EgressState(String model, boolean generate) {
            this.model = model;
            this.generate = generate;
        }
    }

    private static final class PendingCall {
        final int index;
        final String name;
        final StringBuilder arguments = new StringBuilder();

        PendingCall(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    private List<Object> lines(ChatChunk chunk, EgressState state) {
        List<Object> out = new ArrayList<>(2);
        if (chunk.reasoningDelta() != null) {
            out.add(emit(state, state.generate
                    ? OllamaDtos.Line.generate(state.model, "", chunk.reasoningDelta())
                    : OllamaDtos.Line.chat(state.model, OllamaDtos.OutMessage.of("", chunk.reasoningDelta(), null))));
        }
        if (chunk.contentDelta() != null) {
            out.add(emit(state, state.generate
                    ? OllamaDtos.Line.generate(state.model, chunk.contentDelta(), null)
                    : OllamaDtos.Line.chat(state.model, OllamaDtos.OutMessage.of(chunk.contentDelta(), null, null))));
        }
        if (chunk.toolCallDelta() != null) {
            ToolCallDelta d = chunk.toolCallDelta();
            PendingCall last = state.calls.isEmpty() ? null : state.calls.getLast();
            if (last == null || d.id() != null || last.index != d.index()) {
                last = new PendingCall(d.index(), d.name() == null ? "" : d.name());
                state.calls.add(last);
            }
            if (d.argumentsFragment() != null) {
                last.arguments.append(d.argumentsFragment());
            }
        }
        if (chunk.finishReason() != null) {
            out.addAll(finish(state, chunk.finishReason(), chunk.usage()));
        }
        return out;
    }

    private List<Object> finish(EgressState state, String finishReason, TokenUsage usage) {
        if (state.finished) {
            return List.of();
        }
        state.finished = true;
        List<Object> out = new ArrayList<>(2);
        if (!state.calls.isEmpty() && !state.generate) {
            List<OllamaDtos.OutToolCall> calls = state.calls.stream()
                    .map(c -> OllamaDtos.toolCall(mapper, c.name, c.arguments.toString()))
                    .toList();
            out.add(emit(state, OllamaDtos.Line.chat(state.model, OllamaDtos.OutMessage.of("", null, calls))));
        }
        OllamaDtos.Line last = state.generate
                ? OllamaDtos.Line.generate(state.model, "", null)
                : OllamaDtos.Line.chat(state.model, OllamaDtos.OutMessage.of("", null, null));
        out.add(emit(state, last.finished(finishReason, usage)));
        return out;
    }

    private OllamaDtos.Line emit(EgressState state, OllamaDtos.Line line) {
        state.started = true;
        return line;
    }
}
