package com.shreyasnandurkar.botvinnikapi.api.ollama;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Ollama reads every request body as JSON whatever its Content-Type, and its docs use
 * {@code curl -d}, which sends form encoding; without this those requests would get a 415.
 */
@Component
@Order(-110)
public class OllamaJsonBodyFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        MediaType type = request.getHeaders().getContentType();
        if (request.getMethod() != HttpMethod.POST || !request.getPath().value().startsWith("/api/")
                || (type != null && MediaType.APPLICATION_JSON.isCompatibleWith(type))) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate()
                .request(request.mutate().headers(h -> h.setContentType(MediaType.APPLICATION_JSON)).build())
                .build());
    }
}
