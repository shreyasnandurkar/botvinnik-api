package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns normalized ChatChunks into the payloads of an OpenAI SSE stream.
 * Emits raw Strings — Spring's SSE writer adds the "data:" framing verbatim, so the
 * terminal sentinel arrives as the literal {@code data: [DONE]} OpenAI SDKs require.
 */
@Component
public class OpenAiSseEncoder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSseEncoder.class);

    private static final String DONE = "[DONE]";

    private final ObjectMapper mapper;

    public OpenAiSseEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param includeUsage stream_options.include_usage — append a usage-only frame
     *                     (choices: []) before [DONE], mirroring OpenAI's opt-in.
     */
    public Flux<String> encode(Flux<ChatChunk> chunks, String model, boolean includeUsage) {
        return Flux.defer(() -> {
            String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
            long created = Instant.now().getEpochSecond();
            EgressState state = new EgressState();
            return chunks
                    .concatMap(chunk -> Flux.fromIterable(frames(chunk, state, id, created, model)))
                    // §10 point of no return: before the first frame the 200 is not committed,
                    // so the error must propagate (JSON error now, failover later). After it,
                    // the only honest move is an in-band error chunk followed by [DONE].
                    .onErrorResume(e -> {
                        if (!state.roleSent) {
                            return Flux.error(e);
                        }
                        log.warn("Stream for model '{}' died after frames were flushed; emitting error chunk: {}",
                                model, e.toString());
                        return Flux.just(errorFrame(e));
                    })
                    .concatWith(Flux.defer(() -> includeUsage && state.usage != null
                            ? Flux.just(usageFrame(state.usage, id, created, model))
                            : Flux.empty()))
                    .concatWith(Flux.just(DONE));
        });
    }

    /** Per-stream mutable state: role goes out once, usage is held for the opt-in frame. */
    private static final class EgressState {
        boolean roleSent;
        TokenUsage usage;
    }

    private List<String> frames(ChatChunk chunk, EgressState state, String id, long created, String model) {
        List<String> out = new ArrayList<>(2);
        if (chunk.reasoningDelta() != null) {
            out.add(frame(id, created, model,
                    new OpenAiDtos.Delta(role(state), null, chunk.reasoningDelta(), null), null));
        }
        if (chunk.contentDelta() != null) {
            out.add(frame(id, created, model,
                    new OpenAiDtos.Delta(role(state), chunk.contentDelta(), null, null), null));
        }
        if (chunk.toolCallDelta() != null) {
            ToolCallDelta d = chunk.toolCallDelta();
            out.add(frame(id, created, model,
                    new OpenAiDtos.Delta(role(state), null, null, List.of(new OpenAiDtos.DeltaToolCall(
                            d.index(), d.id(), "function",
                            new OpenAiDtos.OutgoingFunctionCall(d.name(), d.argumentsFragment())))),
                    null));
        }
        if (chunk.finishReason() != null) {
            state.usage = chunk.usage();
            out.add(frame(id, created, model,
                    new OpenAiDtos.Delta(role(state), null, null, null), chunk.finishReason()));
        }
        return out;
    }

    /** OpenAI sends delta.role on the first frame of the stream only. */
    private static String role(EgressState state) {
        if (state.roleSent) {
            return null;
        }
        state.roleSent = true;
        return "assistant";
    }

    private String frame(String id, long created, String model, OpenAiDtos.Delta delta, String finishReason) {
        return mapper.writeValueAsString(new OpenAiDtos.ChatCompletionChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new OpenAiDtos.ChunkChoice(0, delta, finishReason)), null));
    }

    private String errorFrame(Throwable e) {
        String message = e instanceof GatewayException ? e.getMessage()
                : "The provider connection was interrupted mid-stream.";
        String code = e instanceof GatewayException ge && ge.code() != null
                ? ge.code() : "stream_interrupted";
        return mapper.writeValueAsString(new OpenAiDtos.ErrorBody(
                new OpenAiDtos.ErrorDetail(message, "api_error", null, code)));
    }

    private String usageFrame(TokenUsage usage, String id, long created, String model) {
        return mapper.writeValueAsString(new OpenAiDtos.ChatCompletionChunk(
                id, "chat.completion.chunk", created, model, List.of(),
                new OpenAiDtos.Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens())));
    }
}