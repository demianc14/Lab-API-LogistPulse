package com.logistpulse.lab.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistpulse.lab.domain.Order;
import com.logistpulse.lab.domain.OrderStatus;
import com.logistpulse.lab.domain.Warehouse;
import com.logistpulse.lab.repo.OrderRepository;
import com.logistpulse.lab.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Parte 7.5: el mismo mensaje entregado dos veces no debe fallar ni cambiar nada la segunda vez. */
class FleetListenerIdempotencyTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService service = new OrderService(orders, null, null, null, null, null,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), new SimpleMeterRegistry());
    private final FleetListener listener = new FleetListener(service);

    private Order orderIn(OrderStatus status) {
        Order o = new Order("LP-T1", "cliente", 1, 0, new Warehouse("W", 0, 0));
        o.setStatus(status);
        when(orders.findById(7L)).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    void firstDeliveryMovesDispatchedToInTransit() {
        Order o = orderIn(OrderStatus.DISPATCHED);
        listener.onDispatched("7");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
    }

    @Test
    void secondDeliveryOfTheSameMessageIsIgnored() {
        Order o = orderIn(OrderStatus.DISPATCHED);
        listener.onDispatched("7");
        listener.onDispatched("7");          // duplicado: antes lanzaba "Transición ilegal" y el broker lo reencolaba sin fin
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        verify(orders, times(2)).findById(7L);
    }

    @Test
    void staleMessageForAnOrderThatMovedOnIsIgnored() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED, OrderStatus.RETURNED,
                OrderStatus.CANCELLED, OrderStatus.CREATED}) {
            Order o = orderIn(s);
            listener.onDispatched("7");
            assertThat(o.getStatus()).as("estado %s no debe cambiar", s).isEqualTo(s);
        }
    }

    @Test
    void messageForAMissingOrderIsIgnored() {
        when(orders.findById(99L)).thenReturn(Optional.empty());
        listener.onDispatched("99");         // no lanza: no hay nada que reintentar
        assertThat(service.startTransit(99L)).isFalse();
    }
}
