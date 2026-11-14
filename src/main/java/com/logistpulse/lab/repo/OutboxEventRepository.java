package com.logistpulse.lab.repo;

import com.logistpulse.lab.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Eventos aún sin publicar, en el orden en que se crearon. */
    List<OutboxEvent> findTop50BySentAtIsNullOrderByIdAsc();

    long countBySentAtIsNull();
}
