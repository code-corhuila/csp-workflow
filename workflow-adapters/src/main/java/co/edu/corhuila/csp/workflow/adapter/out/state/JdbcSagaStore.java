package co.edu.corhuila.csp.workflow.adapter.out.state;

import co.edu.corhuila.csp.workflow.application.port.out.SagaAlreadyStartedException;
import co.edu.corhuila.csp.workflow.application.port.out.SagaStore;
import co.edu.corhuila.csp.workflow.application.port.out.StaleSagaException;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.SagaSnapshot;
import co.edu.corhuila.csp.workflow.domain.saga.SagaStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The state of the sagas in schema {@code workflow} of PostgreSQL (ADR-065). A new saga is inserted with version 1 and
 * every later write is guarded by the version it read, so two writers never overwrite each other. The unique
 * constraint of the user and the Idempotency-Key decides two starts that arrive together.
 */
public final class JdbcSagaStore implements SagaStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "id, saga_name, status, user_id, idempotency_key, input, completed_steps, "
            + "compensated_steps, current_step, failed_step, failure_detail, reservation_id, correlation_id, version, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcSagaStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void save(SagaInstance saga) {
        SagaSnapshot s = saga.snapshot();
        if (s.version() == 0) {
            insert(s);
            saga.persistedAs(1);
        } else {
            update(s);
            saga.persistedAs(s.version() + 1);
        }
    }

    private void insert(SagaSnapshot s) {
        try {
            jdbc.update("INSERT INTO workflow.saga (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    s.id(), s.sagaName(), s.status().name(), s.userId(), s.idempotencyKey(), write(s.input()),
                    steps(s.completedSteps()), steps(s.compensatedSteps()), s.currentStep(), s.failedStep(),
                    s.failureDetail(), s.reservationId(), s.correlationId(), at(s.createdAt()), at(s.updatedAt()));
        } catch (DuplicateKeyException e) {
            throw new SagaAlreadyStartedException();
        }
    }

    private void update(SagaSnapshot s) {
        int changed = jdbc.update("UPDATE workflow.saga SET status = ?, completed_steps = ?, compensated_steps = ?, "
                        + "current_step = ?, failed_step = ?, failure_detail = ?, reservation_id = ?, version = version + 1, "
                        + "updated_at = ? WHERE id = ? AND version = ?",
                s.status().name(), steps(s.completedSteps()), steps(s.compensatedSteps()), s.currentStep(),
                s.failedStep(), s.failureDetail(), s.reservationId(), at(s.updatedAt()), s.id(), s.version());
        if (changed == 0) {
            throw new StaleSagaException(s.id());
        }
    }

    @Override
    public Optional<SagaInstance> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE id = ?", JdbcSagaStore::restore, id)
                .stream().findFirst();
    }

    @Override
    public Optional<SagaInstance> findByUserAndKey(UUID userId, String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE user_id = ? AND idempotency_key = ?",
                JdbcSagaStore::restore, userId, idempotencyKey).stream().findFirst();
    }

    /** Served by {@code idx_saga_running_updated_at}: the sagas a restart may have cut. */
    @Override
    public List<SagaInstance> findRunningUpdatedBefore(Instant cutoff) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga WHERE status = 'RUNNING' AND updated_at < ? "
                + "ORDER BY updated_at", JdbcSagaStore::restore, at(cutoff));
    }

    private static SagaInstance restore(ResultSet row, int ignored) throws SQLException {
        return SagaInstance.restore(new SagaSnapshot(row.getObject("id", UUID.class), row.getString("saga_name"),
                row.getObject("user_id", UUID.class), row.getString("idempotency_key"), row.getString("correlation_id"), read(row.getString("input")),
                SagaStatus.valueOf(row.getString("status")), list(row.getArray("completed_steps")),
                list(row.getArray("compensated_steps")), row.getString("current_step"), row.getString("failed_step"),
                row.getString("failure_detail"), row.getObject("reservation_id", UUID.class), row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    private static String write(SagaInput input) {
        try {
            return JSON.writeValueAsString(Map.of("showtimeId", input.showtimeId(), "seatLabels", input.seatLabels()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SagaInput read(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            List<String> labels = new ArrayList<>();
            node.get("seatLabels").forEach(label -> labels.add(label.asText()));
            return new SagaInput(UUID.fromString(node.get("showtimeId").asText()), labels);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String[] steps(List<String> steps) {
        return steps.toArray(String[]::new);
    }

    private static List<String> list(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
