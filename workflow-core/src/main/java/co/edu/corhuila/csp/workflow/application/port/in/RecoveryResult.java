package co.edu.corhuila.csp.workflow.application.port.in;

import java.util.List;
import java.util.UUID;

/** What a recovery did: the sagas it resumed, the ones another writer had already moved and the ones that failed. */
public record RecoveryResult(List<UUID> resumed, List<UUID> skipped, List<UUID> failed) { }
