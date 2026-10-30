package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.shreyasnandurkar.botvinnikapi.api.ClientProtocol;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/** OpenAI Chat Completions, the protocol most AI tools speak. */
@Component
public class OpenAiProtocol implements ClientProtocol<OpenAiChatCompletionRequest> {

    static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final OpenAiSseEncoder sseEncoder;

    public OpenAiProtocol(OpenAiSseEncoder sseEncoder) {
        this.sseEncoder = sseEncoder;
    }

    @Override
    public List<String> paths() {
        return List.of(CHAT_COMPLETIONS_PATH);
    }

    @Override
    public InboundChat toChatRequest(OpenAiChatCompletionRequest body) {
        body.validate();
        return new InboundChat(body.model, Boolean.TRUE.equals(body.stream), body.promptChars(),
                body::toChatRequest);
    }

    @Override
    public Object encodeResponse(ChatResponse response) {
        return OpenAiDtos.ChatCompletionResponse.from(response);
    }

    @Override
    public Flux<String> encodeStream(Flux<ChatChunk> chunks, OpenAiChatCompletionRequest body, String model) {
        return sseEncoder.encode(chunks, model, body.includeStreamUsage());
    }

    @Override
    public Object encodeError(int status, String message, String type, String param, String code) {
        return new OpenAiDtos.ErrorBody(new OpenAiDtos.ErrorDetail(message, type, param, code));
    }
}
