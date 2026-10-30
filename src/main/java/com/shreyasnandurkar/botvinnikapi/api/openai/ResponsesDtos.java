package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;

import java.util.List;
import java.util.UUID;

/** Outbound OpenAI Responses shapes: the response object, its output items and stream events. */
public final class ResponsesDtos {

    private ResponsesDtos() {
    }

    static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** Fields the SDKs read on every response; the gateway never stores, so store is always false. */
    public record Response(
            String id,
            String object,
            @JsonProperty("created_at") long createdAt,
            String status,
            ErrorObj error,
            @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
            String model,
            List<Object> output,
            @JsonProperty("parallel_tool_calls") boolean parallelToolCalls,
            @JsonProperty("previous_response_id") String previousResponseId,
            boolean store,
            @JsonProperty("tool_choice") String toolChoice,
            Usage usage) {

        static Response of(String id, long createdAt, String model, String status,
                           IncompleteDetails incomplete, ErrorObj error, List<Object> output, Usage usage) {
            return new Response(id, "response", createdAt, status, error, incomplete, model, output,
                    true, null, false, "auto", usage);
        }
    }

    public record IncompleteDetails(String reason) {
    }

    public record ErrorObj(String code, String message) {
    }

    // ── output items ────────────────────────────────────────────────────────

    public record MessageItem(String type, String id, String status, String role, List<OutputText> content) {

        static MessageItem of(String id, String status, List<OutputText> content) {
            return new MessageItem("message", id, status, "assistant", content);
        }
    }

    public record OutputText(String type, String text, List<Object> annotations) {

        static OutputText of(String text) {
            return new OutputText("output_text", text, List.of());
        }
    }

    public record FunctionCallItem(String type, String id, @JsonProperty("call_id") String callId,
                                   String name, String arguments, String status) {

        static FunctionCallItem of(String id, String callId, String name, String arguments, String status) {
            return new FunctionCallItem("function_call", id, callId, name, arguments, status);
        }
    }

    /** Backends return raw thinking traces; summary_text is the reasoning shape every SDK version renders. */
    public record ReasoningItem(String type, String id, List<SummaryText> summary) {

        static ReasoningItem of(String id, List<SummaryText> summary) {
            return new ReasoningItem("reasoning", id, summary);
        }
    }

    public record SummaryText(String type, String text) {

        static SummaryText of(String text) {
            return new SummaryText("summary_text", text);
        }
    }

    public record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("input_tokens_details") InputDetails inputTokensDetails,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("output_tokens_details") OutputDetails outputTokensDetails,
            @JsonProperty("total_tokens") int totalTokens) {

        static Usage of(TokenUsage usage) {
            TokenUsage u = usage == null ? TokenUsage.ZERO : usage;
            return new Usage(u.promptTokens(), new InputDetails(0), u.completionTokens(),
                    new OutputDetails(0), u.totalTokens());
        }
    }

    public record InputDetails(@JsonProperty("cached_tokens") int cachedTokens) {
    }

    public record OutputDetails(@JsonProperty("reasoning_tokens") int reasoningTokens) {
    }

    // ── stream events (each carries its own type and a sequence_number) ─────

    public record ResponseEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                Response response) {
    }

    public record OutputItemEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                  @JsonProperty("output_index") int outputIndex, Object item) {
    }

    public record ContentPartEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                   @JsonProperty("item_id") String itemId,
                                   @JsonProperty("output_index") int outputIndex,
                                   @JsonProperty("content_index") int contentIndex, OutputText part) {
    }

    /** output_text.delta carries delta, output_text.done carries text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                            @JsonProperty("item_id") String itemId,
                            @JsonProperty("output_index") int outputIndex,
                            @JsonProperty("content_index") int contentIndex,
                            String delta, String text, List<Object> logprobs) {
    }

    public record SummaryPartEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                   @JsonProperty("item_id") String itemId,
                                   @JsonProperty("output_index") int outputIndex,
                                   @JsonProperty("summary_index") int summaryIndex, SummaryText part) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SummaryTextEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                   @JsonProperty("item_id") String itemId,
                                   @JsonProperty("output_index") int outputIndex,
                                   @JsonProperty("summary_index") int summaryIndex,
                                   String delta, String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionArgsEvent(String type, @JsonProperty("sequence_number") int sequenceNumber,
                                    @JsonProperty("item_id") String itemId,
                                    @JsonProperty("output_index") int outputIndex,
                                    String delta, String arguments) {
    }
}
