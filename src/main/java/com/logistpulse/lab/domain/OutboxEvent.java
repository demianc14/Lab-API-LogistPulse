package com.logistpulse.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * Patrón Outbox: el evento se guarda en la MISMA transacción que el cambio de estado.
 * Un proceso aparte lo publica al broker y lo marca como enviado (sentAt != null).
 */
@Entity
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 200)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** null mientras el evento no se haya publicado. */
    private Instant sentAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void markSent(Instant when) { this.sentAt = when; }
}
