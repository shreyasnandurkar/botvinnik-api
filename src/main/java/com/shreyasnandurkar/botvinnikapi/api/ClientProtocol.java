package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * The client-side mirror of LLMProvider: one implementation per wire protocol apps speak to the gateway.
 *
 * @param <B> the protocol's parsed request body
 */
public interface ClientProtocol<B> {

    /** URL paths this protocol serves; also selects the error shape for those paths. */
    List<String> paths();

    /** Validates the body (throwing InvalidRequestException naming the bad param) and maps it to common types. */
    InboundChat toChatRequest(B body);

    Object encodeResponse(ChatResponse response);

    /** SSE elements (payload strings or ServerSentEvents), including the protocol's in-band error and ending. */
    Flux<?> encodeStream(Flux<ChatChunk> chunks, B body, String model);

    /** Content type of encodeStream's output; Ollama streams NDJSON rather than SSE. */
    default MediaType streamMediaType() {
        return MediaType.TEXT_EVENT_STREAM;
    }

    Object encodeError(int status, String message, String type, String param, String code);

    /**
     * @param forModel builds the ChatRequest once routing has resolved the backend model name
     */
    record InboundChat(String model, boolean stream, long promptChars, Function<String, ChatRequest> forModel) {
    }
}
