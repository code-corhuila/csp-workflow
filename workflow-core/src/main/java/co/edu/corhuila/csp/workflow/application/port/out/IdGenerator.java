package co.edu.corhuila.csp.workflow.application.port.out;

import java.util.UUID;

public interface IdGenerator {

    UUID next();
}
