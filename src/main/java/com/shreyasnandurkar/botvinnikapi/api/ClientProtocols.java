package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.openai.OpenAiProtocol;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picks the client protocol by URL path; anything unclaimed (control plane included) answers OpenAI-style. */
@Component
public class ClientProtocols {

    private final Map<String, ClientProtocol<?>> byPath = new HashMap<>();
    private final ClientProtocol<?> fallback;

    public ClientProtocols(List<ClientProtocol<?>> protocols, OpenAiProtocol openAi) {
        for (ClientProtocol<?> protocol : protocols) {
            protocol.paths().forEach(path -> byPath.put(path, protocol));
        }
        this.fallback = openAi;
    }

    public ClientProtocol<?> forPath(String path) {
        return byPath.getOrDefault(path, fallback);
    }
}
