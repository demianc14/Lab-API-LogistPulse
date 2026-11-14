package com.logistpulse.lab.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Registra la métrica http.server.requests (method, uri, status, outcome) para los endpoints Jersey.
 * Spring Boot 4 ya no la crea automáticamente para Jersey, y los paneles de Grafana y los SLO la necesitan.
 * El "uri" se normaliza (/api/orders/42 → /api/orders/{id}) para no generar una serie por cada pedido.
 */
@Component
@Provider
public class HttpMetricsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String START = "lp.http.start";

    private final MeterRegistry registry;

    public HttpMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        request.setProperty(START, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object start = request.getProperty(START);
        if (start == null) {
            return;
        }
        int status = response.getStatus();
        Timer.builder("http.server.requests")
                .tag("method", request.getMethod())
                .tag("uri", normalize(request.getUriInfo().getPath()))
                .tag("status", String.valueOf(status))
                .tag("outcome", outcome(status))
                .register(registry)
                .record(System.nanoTime() - (Long) start, TimeUnit.NANOSECONDS);
    }

    static String normalize(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return p.replaceAll("/\\d+", "/{id}");
    }

    static String outcome(int status) {
        if (status < 200) return "INFORMATIONAL";
        if (status < 300) return "SUCCESS";
        if (status < 400) return "REDIRECTION";
        if (status < 500) return "CLIENT_ERROR";
        return "SERVER_ERROR";
    }
}
