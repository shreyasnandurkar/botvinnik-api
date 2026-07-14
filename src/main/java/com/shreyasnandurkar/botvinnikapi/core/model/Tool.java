package com.shreyasnandurkar.botvinnikapi.core.model;

import tools.jackson.databind.JsonNode;

/**
 * A tool (function) the client makes available to the model.
 *
 * @param name        function name
 * @param description what the function does
 * @param parameters  JSON Schema for the arguments, passed through untouched
 */
public record Tool(String name, String description, JsonNode parameters) {
}
