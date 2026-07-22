package com.shreyasnandurkar.botvinnikapi.telemetry;

import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/** Longest model-prefix match; unpriced models (local inference) cost $0. */
@Component
public class PriceBook {

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final List<GatewayProperties.PriceProps> prices;

    public PriceBook(GatewayProperties properties) {
        this.prices = properties.pricing().stream()
                .sorted(Comparator.comparingInt((GatewayProperties.PriceProps p) -> p.model().length()).reversed())
                .toList();
    }

    public BigDecimal cost(String model, Integer promptTokens, Integer completionTokens) {
        if (model == null) {
            return BigDecimal.ZERO;
        }
        for (GatewayProperties.PriceProps price : prices) {
            if (model.startsWith(price.model())) {
                BigDecimal input = tokenCost(price.inputUsdPer1m(), promptTokens);
                BigDecimal output = tokenCost(price.outputUsdPer1m(), completionTokens);
                return input.add(output);
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal tokenCost(BigDecimal usdPer1m, Integer tokens) {
        if (usdPer1m == null || tokens == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return usdPer1m.multiply(BigDecimal.valueOf(tokens)).divide(PER_MILLION, MathContext.DECIMAL64);
    }
}
