package com.shreyasnandurkar.botvinnikapi.api.openai;

import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Responses event sequence: every item added, filled and done, then one terminal response event. */
class ResponsesSseEncoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesSseEncoder encoder = new ResponsesSseEncoder(mapper);

    private List<ServerSentEvent<String>> encode(Flux<ChatChunk> chunks) {
        return encoder.encode(chunks, "qwen3:4b").collectList().block();
    }

    private static List<String> names(List<ServerSentEvent<String>> events) {
        return events.stream().map(ServerSentEvent::event).toList();
    }

    private JsonNode data(ServerSentEvent<String> event) {
        return mapper.readTree(event.data());
    }

    @Test
    void textStreamFollowsTheResponseEventSequence() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                ChatChunk.content("Hel"), ChatChunk.content("lo"),
                new ChatChunk(null, null, null, "stop", new TokenUsage(7, 2))));

        assertThat(names(events)).containsExactly("response.created", "response.in_progress",
                "response.output_item.added", "response.content_part.added",
                "response.output_text.delta", "response.output_text.delta",
                "response.output_text.done", "response.content_part.done", "response.output_item.done",
                "response.completed");
        for (int i = 0; i < events.size(); i++) {
            assertThat(data(events.get(i)).path("sequence_number").asInt()).isEqualTo(i);
            assertThat(data(events.get(i)).path("type").asString()).isEqualTo(events.get(i).event());
        }
        assertThat(data(events.get(0)).at("/response/id").asString()).startsWith("resp_");
        assertThat(data(events.get(0)).at("/response/status").asString()).isEqualTo("in_progress");
        assertThat(data(events.get(6)).path("text").asString()).isEqualTo("Hello");

        JsonNode done = data(events.getLast()).path("response");
        assertThat(done.path("status").asString()).isEqualTo("completed");
        assertThat(done.at("/output/0/type").asString()).isEqualTo("message");
        assertThat(done.at("/output/0/content/0/text").asString()).isEqualTo("Hello");
        assertThat(done.at("/usage/input_tokens").asInt()).isEqualTo(7);
        assertThat(done.at("/usage/output_tokens").asInt()).isEqualTo(2);
        assertThat(done.at("/usage/total_tokens").asInt()).isEqualTo(9);
    }

    @Test
    void reasoningTextAndToolCallBecomeSeparateOutputItems() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                ChatChunk.reasoning("hmm"),
                ChatChunk.content("Checking."),
                new ChatChunk(null, null, new ToolCallDelta(0, "call_1", "get_weather", ""), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "{\"city\":"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "\"Pune\"}"), null, null),
                new ChatChunk(null, null, null, "tool_calls", new TokenUsage(5, 9))));

        List<JsonNode> added = events.stream().filter(e -> "response.output_item.added".equals(e.event()))
                .map(this::data).toList();
        assertThat(added).extracting(a -> a.at("/item/type").asString())
                .containsExactly("reasoning", "message", "function_call");
        assertThat(added).extracting(a -> a.path("output_index").asInt()).containsExactly(0, 1, 2);
        assertThat(added.get(2).at("/item/call_id").asString()).isEqualTo("call_1");

        JsonNode argsDone = events.stream().filter(e -> "response.function_call_arguments.done".equals(e.event()))
                .map(this::data).findFirst().orElseThrow();
        assertThat(argsDone.path("arguments").asString()).isEqualTo("{\"city\":\"Pune\"}");

        JsonNode done = data(events.getLast()).path("response");
        assertThat(done.at("/output/0/summary/0/text").asString()).isEqualTo("hmm");
        assertThat(done.at("/output/1/content/0/text").asString()).isEqualTo("Checking.");
        assertThat(done.at("/output/2/name").asString()).isEqualTo("get_weather");
        assertThat(done.at("/output/2/arguments").asString()).isEqualTo("{\"city\":\"Pune\"}");
        assertThat(done.path("status").asString()).isEqualTo("completed");
    }

    @Test
    void parallelToolCallsEachGetTheirOwnItem() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                new ChatChunk(null, null, new ToolCallDelta(0, "call_1", "a", "{}"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(1, "call_2", "b", "{}"), null, null),
                new ChatChunk(null, null, null, "tool_calls", null)));

        JsonNode output = data(events.getLast()).at("/response/output");
        assertThat(output).hasSize(2);
        assertThat(output.get(0).path("call_id").asString()).isEqualTo("call_1");
        assertThat(output.get(1).path("call_id").asString()).isEqualTo("call_2");
    }

    @Test
    void lengthFinishIsIncompleteNotFailed() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                ChatChunk.content("cut"), new ChatChunk(null, null, null, "length", null)));

        assertThat(events.getLast().event()).isEqualTo("response.incomplete");
        JsonNode response = data(events.getLast()).path("response");
        assertThat(response.path("status").asString()).isEqualTo("incomplete");
        assertThat(response.at("/incomplete_details/reason").asString()).isEqualTo("max_output_tokens");
    }

    @Test
    void errorAfterStartBecomesResponseFailed() {
        List<ServerSentEvent<String>> events = encode(Flux.concat(
                Flux.just(ChatChunk.content("Hi")),
                Flux.error(new UpstreamException("p", 529, "overloaded"))));

        assertThat(events.getLast().event()).isEqualTo("response.failed");
        assertThat(names(events)).doesNotContain("response.completed");
        JsonNode response = data(events.getLast()).path("response");
        assertThat(response.path("status").asString()).isEqualTo("failed");
        assertThat(response.at("/error/message").asString()).isNotBlank();
    }

    @Test
    void errorBeforeFirstEventPropagatesSoTheRouterCanFailOver() {
        assertThatThrownBy(() -> encode(Flux.error(new UpstreamException("p", 500, "boom"))))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    void streamEndingWithoutFinishStillCompletes() {
        List<ServerSentEvent<String>> events = encode(Flux.just(ChatChunk.content("partial")));

        assertThat(names(events)).endsWith("response.output_item.done", "response.completed");
        assertThat(data(events.getLast()).at("/response/output/0/content/0/text").asString()).isEqualTo("partial");
    }
}
