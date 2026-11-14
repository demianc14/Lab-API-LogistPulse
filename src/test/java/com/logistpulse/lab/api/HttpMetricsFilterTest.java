package com.logistpulse.lab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpMetricsFilterTest {

    @Test
    void numericSegmentsBecomeAPlaceholderToAvoidOneSeriesPerOrder() {
        assertThat(HttpMetricsFilter.normalize("api/orders/42")).isEqualTo("/api/orders/{id}");
        assertThat(HttpMetricsFilter.normalize("/api/orders/42/dispatch")).isEqualTo("/api/orders/{id}/dispatch");
        assertThat(HttpMetricsFilter.normalize("api/robots")).isEqualTo("/api/robots");
    }

    @Test
    void outcomeFollowsTheStatusClass() {
        assertThat(HttpMetricsFilter.outcome(200)).isEqualTo("SUCCESS");
        assertThat(HttpMetricsFilter.outcome(409)).isEqualTo("CLIENT_ERROR");
        assertThat(HttpMetricsFilter.outcome(503)).isEqualTo("SERVER_ERROR");
    }
}
