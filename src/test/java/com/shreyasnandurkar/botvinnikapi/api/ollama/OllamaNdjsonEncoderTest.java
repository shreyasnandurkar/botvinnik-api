package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.shreyasnandurkar.botvinnikapi.core.error.UpstreamException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatChunk;
import com.shreyasnandurkar.botvinnikapi.core.model.TokenUsage;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ollama's NDJSON stream: one object per line, tool calls whole, usage on the done line. */
class OllamaNdjsonEncoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OllamaNdjsonEncoder encoder = new OllamaNdjsonEncoder(mapper);

    private List<JsonNode> encode(Flux<ChatChunk> chunks, boolean generate) {
        List<Object> lines = encoder.encode(chunks, "qwen3:4b", generate).collectList().block();
        return lines.stream().map(line -> mapper.readTree(mapper.writeValueAsString(line))).toList();
    }

    @Test
    void chatStreamEndsWithDoneLineCarryingUsage() {
        List<JsonNode> lines = encode(Flux.just(
                ChatChunk.reasoning("hmm"), ChatChunk.content("Hel"), ChatChunk.content("lo"),
                new ChatChunk(null, null, null, "stop", new TokenUsage(7, 2))), false);

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).at("/message/thinking").asString()).isEqualTo("hmm");
        assertThat(lines.get(1).at("/message/role").asString()).isEqualTo("assistant");
        assertThat(lines.get(1).at("/message/content").asString()).isEqualTo("Hel");
        assertThat(lines.get(1).path("done").asBoolean()).isFalse();
        assertThat(lines.get(1).has("prompt_eval_count")).isFalse();
        JsonNode done = lines.getLast();
        assertThat(done.path("done").asBoolean()).isTrue();
        assertThat(done.path("done_reason").asString()).isEqualTo("stop");
        assertThat(done.path("prompt_eval_count").asInt()).isEqualTo(7);
        assertThat(done.path("eval_count").asInt()).isEqualTo(2);
        assertThat(done.path("model").asString()).isEqualTo("qwen3:4b");
    }

    @Test
    void generateLinesUseTheResponseField() {
        List<JsonNode> lines = encode(Flux.just(
                ChatChunk.content("Hi"), new ChatChunk(null, null, null, "length", null)), true);

        assertThat(lines.get(0).path("response").asString()).isEqualTo("Hi");
        assertThat(lines.get(0).has("message")).isFalse();
        assertThat(lines.getLast().path("done_reason").asString()).isEqualTo("length");
    }

    @Test
    void fragmentedToolCallsAreSentWholeBeforeTheDoneLine() {
        List<JsonNode> lines = encode(Flux.just(
                new ChatChunk(null, null, new ToolCallDelta(0, "call_1", "get_weather", ""), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "{\"city\":"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(0, null, null, "\"Pune\"}"), null, null),
                new ChatChunk(null, null, new ToolCallDelta(1, "call_2", "get_time", "{}"), null, null),
                new ChatChunk(null, null, null, "tool_calls", new TokenUsage(5, 9))), false);

        assertThat(lines).hasSize(2);
        JsonNode calls = lines.get(0).at("/message/tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).at("/function/name").asString()).isEqualTo("get_weather");
        assertThat(calls.get(0).at("/function/arguments/city").asString()).isEqualTo("Pune");
        assertThat(calls.get(1).at("/function/name").asString()).isEqualTo("get_time");
        // Ollama reports "stop" even when the turn ends in tool calls.
        assertThat(lines.get(1).path("done_reason").asString()).isEqualTo("stop");
    }

    @Test
    void errorAfterFirstLineBecomesAnErrorLine() {
        List<JsonNode> lines = encode(Flux.concat(
                Flux.just(ChatChunk.content("Hi")),
                Flux.error(new UpstreamException("p", 529, "overloaded"))), false);

        assertThat(lines).hasSize(2);
        assertThat(lines.getLast().path("error").asString()).isNotBlank();
        assertThat(lines).noneSatisfy(line -> assertThat(line.path("done").asBoolean()).isTrue());
    }

    @Test
    void errorBeforeFirstLinePropagatesSoTheRouterCanFailOver() {
        assertThatThrownBy(() -> encode(Flux.error(new UpstreamException("p", 500, "boom")), false))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    void streamEndingWithoutFinishStillSendsDone() {
        List<JsonNode> lines = encode(Flux.just(ChatChunk.content("partial")), false);

        assertThat(lines.getLast().path("done").asBoolean()).isTrue();
        assertThat(lines.getLast().path("done_reason").asString()).isEqualTo("stop");
    }
}
