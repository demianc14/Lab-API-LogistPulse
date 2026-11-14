package com.logistpulse.lab.messaging;

import com.logistpulse.lab.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Simula la flota: al recibir el evento, el robot sale y el pedido pasa a IN_TRANSIT.
 * Es idempotente: el broker (y el Outbox) entregan "al menos una vez", así que el mismo mensaje puede llegar repetido.
 */
@Component
@ConditionalOnProperty(name = "lab.messaging.enabled", havingValue = "true")
public class FleetListener {

    private static final Logger log = LoggerFactory.getLogger(FleetListener.class);

    private final OrderService orders;

    public FleetListener(OrderService orders) {
        this.orders = orders;
    }

    @RabbitListener(queues = RabbitTopology.DISPATCHED_QUEUE)
    public void onDispatched(String orderId) {
        log.info("Flota recibió order.dispatched id={}", orderId);
        if (orders.startTransit(Long.valueOf(orderId))) {
            log.info("Pedido {} → IN_TRANSIT", orderId);
        } else {
            log.info("Mensaje duplicado u obsoleto para el pedido {} (ya no está DISPATCHED): se ignora", orderId);
        }
    }
}
