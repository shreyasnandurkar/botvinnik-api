package com.shreyasnandurkar.botvinnikapi.telemetry;

public interface RequestTelemetry {

    void record(RequestLogEntry entry);
}
