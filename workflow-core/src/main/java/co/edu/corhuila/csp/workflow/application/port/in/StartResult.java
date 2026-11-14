package co.edu.corhuila.csp.workflow.application.port.in;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;

/** The saga of a start request and whether this request created it ({@code 201}) or replayed it ({@code 200}). */
public record StartResult(SagaInstance saga, boolean created) { }
