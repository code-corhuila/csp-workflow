package co.edu.corhuila.csp.workflow.adapter.in.http;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The {@code Saga} schema of {@code workflow-service.yaml}. It never carries the failure detail of the saga: the
 * answer names the failed step and nothing else (norm 5.8.3).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SagaResponse(String id, String sagaName, String status, List<String> completedSteps, String failedStep,
                           String reservationId, String createdAt, String updatedAt) {

    static SagaResponse from(SagaInstance saga) {
        return new SagaResponse(saga.id().toString(), saga.sagaName(), saga.status().name(), saga.completedSteps(),
                saga.failedStep(), saga.reservationId() == null ? null : saga.reservationId().toString(),
                saga.createdAt().toString(), saga.updatedAt().toString());
    }
}
