package co.edu.corhuila.csp.workflow.domain.saga;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every field of a saga, for a store to persist it and to give it back (norm 5.8.4). The correlation id is null in a
 * saga stored before the store kept it.
 */
public record SagaSnapshot(UUID id, String sagaName, UUID userId, String idempotencyKey, String correlationId, SagaInput input,
                           SagaStatus status, List<String> completedSteps, List<String> compensatedSteps,
                           String currentStep, String failedStep, String failureDetail, UUID reservationId,
                           long version, Instant createdAt, Instant updatedAt) { }
