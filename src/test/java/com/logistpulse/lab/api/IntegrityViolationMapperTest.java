package com.logistpulse.lab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class IntegrityViolationMapperTest {

    private final IntegrityViolationMapper mapper = new IntegrityViolationMapper();

    private static DataIntegrityViolationException violation(String sqlState) {
        var cause = new ConstraintViolationException("x", new SQLException("boom", sqlState), "uk_orders_one_active_per_robot");
        return new DataIntegrityViolationException("no se pudo ejecutar", cause);
    }

    @Test
    void uniqueViolationIsAConcurrencyConflict() {
        assertThat(mapper.toResponse(violation("23505")).getStatus()).isEqualTo(409);
    }

    @Test
    void checkViolationIsAnInternalError() {
        assertThat(mapper.toResponse(violation("23514")).getStatus()).isEqualTo(500);
    }

    @Test
    void notNullViolationIsAnInternalError() {
        assertThat(mapper.toResponse(violation("23502")).getStatus()).isEqualTo(500);
    }
}
