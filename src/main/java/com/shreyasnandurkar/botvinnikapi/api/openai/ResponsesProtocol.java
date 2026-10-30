package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.shreyasnandurkar.botvinnikapi.api.ClientProtocol;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatResponse;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** OpenAI Responses, spoken by the OpenAI Agents SDK and newer OpenAI tooling. Stateless only. */
@Component
public class ResponsesProtocol implements ClientProtocol<ResponsesRequest> {

    static final String RESPONSES_PATH = "/v1/responses";

    private final ResponsesSseEncoder sseEncoder;

    public ResponsesProtocol(ResponsesSseEncoder sseEncoder) {
        this.sseEncoder = sseEncoder;
    }

    @Override
    public List<String> paths() {
        return List.of(RESPONSES_PATH);
    }

    @Override
    public InboundChat toChatRequest(ResponsesRequest body) {
        body.validate();
        return new InboundChat(body.model, Boolean.TRUE.equals(body.stream), body.promptChars(),
                body::toChatRequest);
    }

    @Override
    public Object encodeResponse(ChatResponse resp) {
        List<Object> output = new ArrayList<>();
        if (resp.reasoningContent() != null) {
            output.add(ResponsesDtos.ReasoningItem.of(ResponsesDtos.id("rs_"),
                    List.of(ResponsesDtos.SummaryText.of(resp.reasoningContent()))));
        }
        if (resp.content() != null && !resp.content().isEmpty()) {
            output.add(ResponsesDtos.MessageItem.of(ResponsesDtos.id("msg_"), "completed",
                    List.of(ResponsesDtos.OutputText.of(resp.content()))));
        }
        if (resp.toolCalls() != null) {
            for (ToolCall tc : resp.toolCalls()) {
                output.add(ResponsesDtos.FunctionCallItem.of(ResponsesDtos.id("fc_"), tc.id(), tc.name(),
                        tc.argumentsJson() == null ? "{}" : tc.argumentsJson(), "completed"));
            }
        }
        return ResponsesDtos.Response.of(ResponsesDtos.id("resp_"), Instant.now().getEpochSecond(),
                resp.model(), status(resp.finishReason()), incompleteDetails(resp.finishReason()), null,
                output, ResponsesDtos.Usage.of(resp.usage()));
    }

    @Override
    public Flux<?> encodeStream(Flux<ChatChunk> chunks, ResponsesRequest body, String model) {
        return sseEncoder.encode(chunks, model);
    }

    /** Responses errors use the same envelope as Chat Completions. */
    @Override
    public Object encodeError(int status, String message, String type, String param, String code) {
        return new OpenAiDtos.ErrorBody(new OpenAiDtos.ErrorDetail(message, type, param, code));
    }

    /** A cut-off answer is "incomplete", not an error; the reason says why. */
    static String status(String finishReason) {
        return "length".equals(finishReason) || "content_filter".equals(finishReason)
                ? "incomplete" : "completed";
    }

    static ResponsesDtos.IncompleteDetails incompleteDetails(String finishReason) {
        if ("length".equals(finishReason)) return new ResponsesDtos.IncompleteDetails("max_output_tokens");
        if ("content_filter".equals(finishReason)) return new ResponsesDtos.IncompleteDetails("content_filter");
        return null;
    }
}
