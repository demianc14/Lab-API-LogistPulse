package com.logistpulse.lab.messaging;

import com.logistpulse.lab.domain.OutboxEvent;
import com.logistpulse.lab.repo.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica los eventos pendientes del Outbox y los marca como enviados.
 *
 * Garantía: al-menos-una-vez. Si el proceso cae después de enviar y antes de hacer commit del "enviado",
 * el evento se reenvía al arrancar: por eso el consumidor debe ser idempotente (ver FleetListener).
 * Con lab.messaging.enabled=false (modo lite) solo lo escribe en el log y lo marca como enviado.
 * Limitación: con varias instancias de la API habría que reclamar filas con SELECT ... FOR UPDATE SKIP LOCKED.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final boolean enabled;
    private final ObjectProvider<RabbitTemplate> rabbit;
    // Se actualizan en cada pasada: el scrape de Prometheus no toca la base de datos.
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    public OutboxPublisher(OutboxEventRepository outbox,
                           @Value("${lab.messaging.enabled:false}") boolean enabled,
                           ObjectProvider<RabbitTemplate> rabbit,
                           MeterRegistry registry) {
        this.outbox = outbox;
        this.enabled = enabled;
        this.rabbit = rabbit;
        Gauge.builder("logistpulse.outbox.pending", pendingCount, AtomicLong::get)
                .description("Eventos del Outbox sin publicar (máx. 50 por pasada)").register(registry);
        Gauge.builder("logistpulse.outbox.oldest.pending.seconds", oldestPendingSeconds, AtomicLong::get)
                .description("Antigüedad del evento pendiente más viejo").register(registry);
    }

    /** Devuelve cuántos eventos se publicaron en esta pasada. */
    @Scheduled(fixedDelayString = "${lab.outbox.poll-ms:1000}")
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = outbox.findTop50BySentAtIsNullOrderByIdAsc();
        int sent = 0;
        for (OutboxEvent event : pending) {
            try {
                send(event);
            } catch (RuntimeException e) {
                // El broker no responde: se conserva el orden, se deja pendiente y se reintenta en la próxima pasada.
                log.warn("Outbox: no se pudo publicar el evento {} ({}); se reintentará", event.getId(), e.getMessage());
                break;
            }
            event.markSent(Instant.now());
            sent++;
        }
        updateGauges(pending);
        return sent;
    }

    private void updateGauges(List<OutboxEvent> pending) {
        List<OutboxEvent> remaining = pending.stream().filter(e -> e.getSentAt() == null).toList();
        pendingCount.set(remaining.size());
        oldestPendingSeconds.set(remaining.isEmpty() ? 0
                : Duration.between(remaining.get(0).getCreatedAt(), Instant.now()).toSeconds());
    }

    private void send(OutboxEvent event) {
        if (!enabled) {
            log.info("[lite] evento {} id={} (RabbitMQ desactivado)", event.getType(), event.getPayload());
            return;
        }
        rabbit.getObject().convertAndSend(RabbitTopology.DISPATCHED_QUEUE, event.getPayload());
        log.info("Evento {} publicado id={}", event.getType(), event.getPayload());
    }
}
