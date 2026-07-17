package com.shreyasnandurkar.botvinnikapi.core.streaming;

import com.shreyasnandurkar.botvinnikapi.core.model.ToolCall;
import com.shreyasnandurkar.botvinnikapi.core.model.ToolCallDelta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles streamed tool calls. OpenAI-style
 * providers emit arguments as incremental JSON string fragments keyed by index;
 * no individual fragment parses on its own — only the concatenation does. The id
 * and name arrive on the first fragment only. Feed every delta through
 * {@link #accept}; call {@link #finish()} once the stream completes.
 *
 * <p>Not thread-safe — one instance per stream, driven by the stream's own signals.
 */
public class ToolCallAccumulator {

    private final Map<Integer, String> idByIndex = new HashMap<>();
    private final Map<Integer, String> nameByIndex = new HashMap<>();
    private final Map<Integer, StringBuilder> argsByIndex = new TreeMap<>();

    public void accept(ToolCallDelta delta) {
        if (delta == null) {
            return;
        }
        if (delta.id() != null) {
            idByIndex.putIfAbsent(delta.index(), delta.id());
        }
        if (delta.name() != null) {
            nameByIndex.putIfAbsent(delta.index(), delta.name());
        }
        StringBuilder args = argsByIndex.computeIfAbsent(delta.index(), i -> new StringBuilder());
        if (delta.argumentsFragment() != null) {
            args.append(delta.argumentsFragment());
        }
    }

    public boolean isEmpty() {
        return argsByIndex.isEmpty();
    }

    /** Assemble the completed calls. Buffers that never received arguments become "{}". */
    public List<ToolCall> finish() {
        List<ToolCall> calls = new ArrayList<>(argsByIndex.size());
        for (Map.Entry<Integer, StringBuilder> entry : argsByIndex.entrySet()) {
            String id = idByIndex.get(entry.getKey());
            calls.add(new ToolCall(
                    id != null ? id : ToolCall.generatedId(),
                    nameByIndex.get(entry.getKey()),
                    entry.getValue().isEmpty() ? "{}" : entry.getValue().toString()));
        }
        return calls;
    }
}