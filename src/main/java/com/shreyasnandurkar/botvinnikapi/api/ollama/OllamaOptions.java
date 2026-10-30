package com.shreyasnandurkar.botvinnikapi.api.ollama;

import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The parts of Ollama's options and think fields that mean something to every backend.
 * Other options (num_ctx, num_gpu, keep_alive...) tune a local runtime and are ignored.
 */
final class OllamaOptions {

    private static final Set<String> THINK_LEVELS = Set.of("low", "medium", "high");

    private OllamaOptions() {
    }

    static Double temperature(JsonNode options) {
        return number(options, "temperature");
    }

    static Double topP(JsonNode options) {
        return number(options, "top_p");
    }

    /** num_predict -1 (infinite) and -2 (fill context) both mean "no limit" here. */
    static Integer numPredict(JsonNode options) {
        Double value = number(options, "num_predict");
        return value == null || value < 1 ? null : value.intValue();
    }

    static List<String> stop(JsonNode options) {
        JsonNode stop = options == null ? null : options.get("stop");
        if (stop == null || stop.isNull()) return null;
        if (stop.isString()) return List.of(stop.asString());
        List<String> out = new ArrayList<>();
        stop.forEach(node -> out.add(node.asString()));
        return out.isEmpty() ? null : out;
    }

    static void validateThink(JsonNode think) {
        if (think == null || think.isNull() || think.isBoolean()) return;
        if (!think.isString() || !THINK_LEVELS.contains(think.asString())) {
            throw new InvalidRequestException("'think' must be true, false, \"low\", \"medium\" or \"high\".", "think");
        }
    }

    /** Absent think keeps the gateway default (off); true picks a middle effort. */
    static String reasoningEffort(JsonNode think) {
        if (think == null || think.isNull()) return null;
        if (think.isBoolean()) return think.asBoolean() ? "medium" : "none";
        return think.asString();
    }

    static void rejectFormat(JsonNode format) {
        if (format != null && !format.isNull() && !(format.isString() && format.asString().isEmpty())) {
            throw new InvalidRequestException("'format' (structured output) is not supported.", "format");
        }
    }

    private static Double number(JsonNode options, String key) {
        JsonNode node = options == null ? null : options.get(key);
        return node == null || !node.isNumber() ? null : node.asDouble();
    }
}
