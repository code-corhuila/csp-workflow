package co.edu.corhuila.csp.workflow.adapter;

/**
 * The MDC key that carries the X-Correlation-Id of a request. The inbound adapter sets it and the outbound
 * adapters send it; neither knows the other.
 */
public final class Correlation {

    public static final String MDC_KEY = "correlationId";

    private Correlation() { }
}
