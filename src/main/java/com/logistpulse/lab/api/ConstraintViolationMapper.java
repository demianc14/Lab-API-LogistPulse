package com.logistpulse.lab.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.Map;

/** Bean Validation falló: 400 con la lista de campos inválidos y su mensaje. */
@Provider
public class ConstraintViolationMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException e) {
        List<Map<String, String>> fields = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", fieldName(v), "message", v.getMessage()))
                .toList();
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", 400, "error", "Datos inválidos", "fields", fields))
                .build();
    }

    /** El path llega como "create.input.customer"; nos quedamos con el último nodo. */
    private static String fieldName(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
