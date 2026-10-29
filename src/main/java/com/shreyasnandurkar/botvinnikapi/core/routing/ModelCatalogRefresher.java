package com.shreyasnandurkar.botvinnikapi.core.routing;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Re-discovers models periodically, since backends gain and lose models outside the gateway (ollama pull). */
@Component
public class ModelCatalogRefresher {

    private final ConfigSnapshotService snapshots;
    private final ModelCatalog catalog;
    private final Duration interval;
    private Disposable loop;

    /** Config edits refresh immediately; this only catches changes made on the backends themselves. */
    public ModelCatalogRefresher(ConfigSnapshotService snapshots, ModelCatalog catalog,
                                 @Value("${botvinnik.model-discovery-interval:5m}") Duration interval) {
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.interval = interval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Self-pacing: the next round is scheduled after the previous one finishes, never overlapping.
        loop = Mono.defer(() -> catalog.refresh(snapshots.registry().all()))
                .then(Mono.delay(interval))
                .repeat()
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (loop != null) {
            loop.dispose();
        }
    }
}
