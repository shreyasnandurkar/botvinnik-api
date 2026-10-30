package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.shreyasnandurkar.botvinnikapi.api.ClientProtocol;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/** Ollama /api/generate: a single prompt in, text out in the response field. */
@Component
public class OllamaGenerateProtocol implements ClientProtocol<OllamaGenerateRequest> {

    private final OllamaNdjsonEncoder encoder;

    public OllamaGenerateProtocol(OllamaNdjsonEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public List<String> paths() {
        return List.of("/api/generate");
    }

    @Override
    public InboundChat toChatRequest(OllamaGenerateRequest body) {
        body.validate();
        return new InboundChat(body.model, body.streaming(), body.promptChars(), body::toChatRequest);
    }

    @Override
    public Object encodeResponse(ChatResponse resp) {
        return OllamaDtos.Line.generate(resp.model(), resp.content() == null ? "" : resp.content(),
                resp.reasoningContent()).finished(resp.finishReason(), resp.usage());
    }

    @Override
    public Flux<Object> encodeStream(Flux<ChatChunk> chunks, OllamaGenerateRequest body, String model) {
        return encoder.encode(chunks, model, true);
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
