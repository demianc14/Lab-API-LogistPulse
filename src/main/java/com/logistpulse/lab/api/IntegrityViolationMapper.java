package com.logistpulse.lab.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Violaciones de restricciones de la base de datos.
 * - Unicidad (SQLState 23505), p. ej. el índice "un pedido activo por robot": dos despachos concurrentes
 *   quisieron el mismo AMR y la BD frenó al segundo. Es un conflicto de concurrencia, igual que el @Version: 409.
 * - Cualquier otra (CHECK, NOT NULL, FK) indica un fallo del programa, no del cliente: 500.
 */
@Provider
public class IntegrityViolationMapper implements ExceptionMapper<DataIntegrityViolationException> {

    private static final Logger log = LoggerFactory.getLogger(IntegrityViolationMapper.class);
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Response toResponse(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            return json(409, "Conflicto de concurrencia, reintenta la operación");
        }
        log.error("Violación de integridad no esperada", e);
        return json(500, "Error interno: se violó una restricción de la base de datos");
    }

    static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Response json(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", status, "error", message)).build();
    }
}
