package com.shreyasnandurkar.botvinnikapi.core.streaming;

import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §8: OpenAI streams tool arguments as JSON string fragments — no single fragment
 * parses on its own. The accumulator must reassemble them, keyed by index.
 */
class ToolCallAccumulatorTest {

    @Test
    void reassemblesFragmentsThatDoNotParseIndividually() {
        ToolCallAccumulator acc = new ToolCallAccumulator();

        // The exact fragmentation from the architecture doc's example.
        acc.accept(new ToolCallDelta(0, "call_x", "get_weather", ""));
        acc.accept(new ToolCallDelta(0, null, null, "{\"loc"));
        acc.accept(new ToolCallDelta(0, null, null, "ation\":\"Ba"));
        acc.accept(new ToolCallDelta(0, null, null, "ngalore\"}"));

        List<ToolCall> calls = acc.finish();

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().id()).isEqualTo("call_x");
        assertThat(calls.getFirst().name()).isEqualTo("get_weather");
        assertThat(calls.getFirst().argumentsJson()).isEqualTo("{\"location\":\"Bangalore\"}");
    }

    @Test
    void keepsInterleavedParallelCallsSeparateAndOrderedByIndex() {
        ToolCallAccumulator acc = new ToolCallAccumulator();

        acc.accept(new ToolCallDelta(1, "call_b", "get_time", "{\"tz\":"));
        acc.accept(new ToolCallDelta(0, "call_a", "get_weather", "{\"loc"));
        acc.accept(new ToolCallDelta(1, null, null, "\"IST\"}"));
        acc.accept(new ToolCallDelta(0, null, null, "ation\":\"Pune\"}"));

        List<ToolCall> calls = acc.finish();

        assertThat(calls).extracting(ToolCall::id).containsExactly("call_a", "call_b");
        assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"location\":\"Pune\"}");
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"tz\":\"IST\"}");
    }

    @Test
    void generatesCallIdWhenProviderNeverSentOne() {
        ToolCallAccumulator acc = new ToolCallAccumulator();

        acc.accept(new ToolCallDelta(0, null, "no_args_tool", null));

        List<ToolCall> calls = acc.finish();

        assertThat(calls.getFirst().id()).startsWith("call_");
        // A call that never received arguments must still be valid JSON downstream.
        assertThat(calls.getFirst().argumentsJson()).isEqualTo("{}");
    }

    @Test
    void emptyStreamFinishesEmpty() {
        ToolCallAccumulator acc = new ToolCallAccumulator();

        assertThat(acc.isEmpty()).isTrue();
        assertThat(acc.finish()).isEmpty();
    }
}
