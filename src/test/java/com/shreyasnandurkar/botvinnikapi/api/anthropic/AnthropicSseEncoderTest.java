package com.shreyasnandurkar.botvinnikapi.api.anthropic;

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

/** The event sequence Anthropic SDKs parse: every block opened, filled, and closed in order. */
class AnthropicSseEncoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicSseEncoder encoder = new AnthropicSseEncoder(mapper);

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
    void textStreamFollowsTheMessageEventSequence() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                ChatChunk.content("Hel"), ChatChunk.content("lo"),
                new ChatChunk(null, null, null, "stop", new TokenUsage(7, 2))));

        assertThat(names(events)).containsExactly("message_start", "content_block_start",
                "content_block_delta", "content_block_delta", "content_block_stop", "message_delta", "message_stop");
        JsonNode start = data(events.get(0));
        assertThat(start.at("/message/id").asString()).startsWith("msg_");
        assertThat(start.at("/message/model").asString()).isEqualTo("qwen3:4b");
        assertThat(data(events.get(1)).at("/content_block/type").asString()).isEqualTo("text");
        assertThat(data(events.get(2)).at("/delta/type").asString()).isEqualTo("text_delta");
        assertThat(data(events.get(3)).at("/delta/text").asString()).isEqualTo("lo");
        JsonNode delta = data(events.get(5));
        assertThat(delta.at("/delta/stop_reason").asString()).isEqualTo("end_turn");
        assertThat(delta.at("/usage/input_tokens").asInt()).isEqualTo(7);
        assertThat(delta.at("/usage/output_tokens").asInt()).isEqualTo(2);
    }

    @Test
    void thinkingTextAndToolGetSeparateIndexedBlocks() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                ChatChunk.reasoning("hmm"),
                ChatChunk.content("Checking."),
                new ChatChunk(null, null, new ToolCallDelta(0, "call_1", "get_weather", ""), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "{\"city\":"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "\"Pune\"}"), null, null),
                new ChatChunk(null, null, null, "tool_calls", new TokenUsage(5, 9))));

        assertThat(names(events)).containsExactly("message_start",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop");
        assertThat(data(events.get(1)).at("/content_block/type").asString()).isEqualTo("thinking");
        assertThat(data(events.get(2)).at("/delta/thinking").asString()).isEqualTo("hmm");
        assertThat(data(events.get(4)).path("index").asInt()).isEqualTo(1);
        JsonNode toolStart = data(events.get(7));
        assertThat(toolStart.path("index").asInt()).isEqualTo(2);
        assertThat(toolStart.at("/content_block/type").asString()).isEqualTo("tool_use");
        assertThat(toolStart.at("/content_block/id").asString()).isEqualTo("call_1");
        assertThat(toolStart.at("/content_block/name").asString()).isEqualTo("get_weather");
        assertThat(data(events.get(8)).at("/delta/partial_json").asString()
                + data(events.get(9)).at("/delta/partial_json").asString()).isEqualTo("{\"city\":\"Pune\"}");
        assertThat(data(events.get(11)).at("/delta/stop_reason").asString()).isEqualTo("tool_use");
    }

    @Test
    void parallelToolCallsEachGetTheirOwnBlock() {
        List<ServerSentEvent<String>> events = encode(Flux.just(
                new ChatChunk(null, null, new ToolCallDelta(0, "call_1", "a", "{}"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(1, "call_2", "b", "{}"), null, null),
                new ChatChunk(null, null, null, "tool_calls", null)));

        List<ServerSentEvent<String>> starts = events.stream()
                .filter(e -> "content_block_start".equals(e.event())).toList();
        assertThat(starts).hasSize(2);
        assertThat(data(starts.get(0)).at("/content_block/id").asString()).isEqualTo("call_1");
        assertThat(data(starts.get(1)).at("/content_block/id").asString()).isEqualTo("call_2");
        assertThat(data(starts.get(1)).path("index").asInt()).isEqualTo(1);
    }

    @Test
    void errorAfterStartBecomesFinalErrorEvent() {
        List<ServerSentEvent<String>> events = encode(Flux.concat(
                Flux.just(ChatChunk.content("Hi")),
                Flux.error(new UpstreamException("p", 529, "overloaded"))));

        assertThat(names(events)).containsExactly("message_start", "content_block_start",
                "content_block_delta", "error");
        JsonNode error = data(events.getLast());
        assertThat(error.path("type").asString()).isEqualTo("error");
        // UpstreamException is a gateway 502, which Anthropic calls api_error.
        assertThat(error.at("/error/type").asString()).isEqualTo("api_error");
    }

    @Test
    void errorBeforeFirstEventPropagatesSoTheRouterCanFailOver() {
        assertThatThrownBy(() -> encode(Flux.error(new UpstreamException("p", 500, "boom"))))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    void streamEndingWithoutFinishStillClosesProperly() {
        List<ServerSentEvent<String>> events = encode(Flux.just(ChatChunk.content("partial")));

        assertThat(names(events)).containsExactly("message_start", "content_block_start",
                "content_block_delta", "content_block_stop", "message_delta", "message_stop");
        assertThat(data(events.get(4)).at("/delta/stop_reason").asString()).isEqualTo("end_turn");
    }
}
