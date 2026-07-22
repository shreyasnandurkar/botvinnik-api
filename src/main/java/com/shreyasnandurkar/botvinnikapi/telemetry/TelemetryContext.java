package com.shreyasnandurkar.botvinnikapi.telemetry;

import java.util.UUID;

/** Carried through the Reactor context from the auth filter to the router. */
public record TelemetryContext(UUID apiKeyId, boolean logContent) {

    public static final TelemetryContext NONE = new TelemetryContext(null, false);
}
