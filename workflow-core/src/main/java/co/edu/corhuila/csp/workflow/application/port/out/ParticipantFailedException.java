package co.edu.corhuila.csp.workflow.application.port.out;

/** A participant refused the call or could not be reached. The message is internal detail and never reaches a response. */
public class ParticipantFailedException extends RuntimeException {

    public ParticipantFailedException(String detail) {
        super(detail);
    }
}
