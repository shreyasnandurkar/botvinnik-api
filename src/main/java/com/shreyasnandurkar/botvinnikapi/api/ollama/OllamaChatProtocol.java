package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.shreyasnandurkar.botvinnikapi.api.ClientProtocol;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Ollama /api/chat, spoken by Open WebUI, the ollama libraries and many local-first tools. */
@Component
public class OllamaChatProtocol implements ClientProtocol<OllamaChatRequest> {

    private final OllamaNdjsonEncoder encoder;
    private final ObjectMapper mapper;

    public OllamaChatProtocol(OllamaNdjsonEncoder encoder, ObjectMapper mapper) {
        this.encoder = encoder;
        this.mapper = mapper;
    }

    /** /api/tags and /api/embed are served by OllamaController but answer errors in Ollama's shape too. */
    @Override
    public List<String> paths() {
        return List.of("/api/chat", "/api/tags", "/api/embed");
    }

    @Override
    public InboundChat toChatRequest(OllamaChatRequest body) {
        body.validate();
        return new InboundChat(body.model, body.streaming(), body.promptChars(), body::toChatRequest);
    }

    @Override
    public Object encodeResponse(ChatResponse resp) {
        List<OllamaDtos.OutToolCall> calls = resp.toolCalls() == null || resp.toolCalls().isEmpty() ? null
                : resp.toolCalls().stream()
                .map(tc -> OllamaDtos.toolCall(mapper, tc.name(), tc.argumentsJson()))
                .toList();
        return OllamaDtos.Line.chat(resp.model(), OllamaDtos.OutMessage.of(resp.content(), resp.reasoningContent(), calls))
                .finished(resp.finishReason(), resp.usage());
    }

    @Override
    public Flux<Object> encodeStream(Flux<ChatChunk> chunks, OllamaChatRequest body, String model) {
        return encoder.encode(chunks, model, false);
    }

    @Override
    public MediaType streamMediaType() {
        return MediaType.APPLICATION_NDJSON;
    }

    @Override
    public Object encodeError(int status, String message, String type, String param, String code) {
        return new OllamaDtos.ErrorBody(message);
    }
}
