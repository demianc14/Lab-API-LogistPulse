package com.logistpulse.lab.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistpulse.lab.api.ApiException;
import com.logistpulse.lab.api.dto.OrderDto;
import com.logistpulse.lab.api.dto.OrderInput;
import com.logistpulse.lab.messaging.OutboxPublisher;
import com.logistpulse.lab.repo.OutboxEventRepository;
import com.logistpulse.lab.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** Parte 7.4: el evento nace con el cambio de estado (misma transacción) y solo si el despacho tuvo éxito. */
@SpringBootTest(properties = {
        "lab.outbox.poll-ms=3600000",                  // el planificador no interfiere: publico a mano
        "lab.carrier.base-url=http://localhost:1"})    // transportista inalcanzable: cae al ETA local, rápido
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DispatchOutboxTest {

    @Autowired OrderService service;
    @Autowired OutboxEventRepository outbox;
    @Autowired OutboxPublisher publisher;

    private OrderDto newOrder() {
        return service.create(new OrderInput(null, "cliente", 10, 5));
    }

    @Test
    void successfulDispatchWritesOneEventAndPublisherMarksItSent() {
        OrderDto order = newOrder();
        assertThat(outbox.count()).isZero();

        service.dispatch(order.id());

        assertThat(outbox.findAll()).hasSize(1).first().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("order.dispatched");
            assertThat(e.getPayload()).isEqualTo(String.valueOf(order.id()));
            assertThat(e.getSentAt()).isNull();
        });

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.countBySentAtIsNull()).isZero();
        assertThat(publisher.publishPending()).isZero();     // ya no hay pendientes: no se reenvía
    }

    @Test
    void rejectedDispatchesWriteNoEvent() {
        OrderDto order = newOrder();
        service.dispatch(order.id());
        publisher.publishPending();
        long before = outbox.count();

        assertThatThrownBy(() -> service.dispatch(order.id()))          // ya DISPATCHED → 409
                .isInstanceOf(ApiException.class);
        assertThat(outbox.count()).isEqualTo(before);

        for (int i = 0; i < 6; i++) {                                    // agotar la flota (4 AMR con batería suficiente)
            try {
                service.dispatch(newOrder().id());
            } catch (ApiException expected) {
                // 409 "No hay AMR disponible"
            }
        }
        long afterExhausting = outbox.count();
        assertThatThrownBy(() -> service.dispatch(newOrder().id())).isInstanceOf(ApiException.class);
        assertThat(outbox.count()).isEqualTo(afterExhausting);          // el despacho fallido no deja evento
    }
}
