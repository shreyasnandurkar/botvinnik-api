package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

/** 502 — the provider answered, but with an error status. */
public class UpstreamException extends GatewayException {

    private final int upstreamStatus;

    public UpstreamException(String provider, int upstreamStatus, String upstreamBody) {
        super("Provider '" + provider + "' returned HTTP " + upstreamStatus
                        + (upstreamBody == null || upstreamBody.isBlank() ? "" : ": " + truncate(upstreamBody)),
                HttpStatus.BAD_GATEWAY, "api_error", "upstream_error", null);
        this.upstreamStatus = upstreamStatus;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    private static String truncate(String body) {
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
