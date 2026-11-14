package com.logistpulse.lab.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistpulse.lab.domain.OutboxEvent;
import com.logistpulse.lab.repo.OutboxEventRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

class OutboxPublisherTest {

    private final OutboxEventRepository repo = mock(OutboxEventRepository.class);
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);

    private OutboxPublisher publisher(boolean enabled) {
        when(provider.getObject()).thenReturn(template);
        return new OutboxPublisher(repo, enabled, provider);
    }

    private static OutboxEvent event(String payload) {
        return new OutboxEvent("order.dispatched", payload);
    }

    @Test
    void sendsPendingEventsInOrderAndMarksThemSent() {
        OutboxEvent a = event("1");
        OutboxEvent b = event("2");
        when(repo.findTop50BySentAtIsNullOrderByIdAsc()).thenReturn(List.of(a, b));

        int sent = publisher(true).publishPending();

        assertThat(sent).isEqualTo(2);
        assertThat(a.getSentAt()).isNotNull();
        assertThat(b.getSentAt()).isNotNull();
        verify(template).convertAndSend(RabbitTopology.DISPATCHED_QUEUE, "1");
        verify(template).convertAndSend(RabbitTopology.DISPATCHED_QUEUE, "2");
    }

    @Test
    void brokerDownKeepsEventsPendingAndStopsTheBatch() {
        OutboxEvent a = event("1");
        OutboxEvent b = event("2");
        when(repo.findTop50BySentAtIsNullOrderByIdAsc()).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("broker caído")).when(template).convertAndSend(any(String.class), any(Object.class));

        int sent = publisher(true).publishPending();

        assertThat(sent).isZero();
        assertThat(a.getSentAt()).isNull();      // sigue pendiente: se reintentará
        assertThat(b.getSentAt()).isNull();      // no se salta el orden
    }

    @Test
    void failureMidBatchKeepsTheAlreadySentOnesMarked() {
        OutboxEvent a = event("1");
        OutboxEvent b = event("2");
        when(repo.findTop50BySentAtIsNullOrderByIdAsc()).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("broker caído")).when(template).convertAndSend(eq(RabbitTopology.DISPATCHED_QUEUE), eq("2"));

        int sent = publisher(true).publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(a.getSentAt()).isNotNull();
        assertThat(b.getSentAt()).isNull();
    }

    @Test
    void liteModeMarksAsSentWithoutTouchingTheBroker() {
        OutboxEvent a = event("1");
        when(repo.findTop50BySentAtIsNullOrderByIdAsc()).thenReturn(List.of(a));

        int sent = publisher(false).publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(a.getSentAt()).isNotNull();
        verify(template, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void nothingPendingDoesNothing() {
        when(repo.findTop50BySentAtIsNullOrderByIdAsc()).thenReturn(List.of());
        assertThat(publisher(true).publishPending()).isZero();
        verify(template, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
