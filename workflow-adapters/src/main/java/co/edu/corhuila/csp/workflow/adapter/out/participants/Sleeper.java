package co.edu.corhuila.csp.workflow.adapter.out.participants;

/** Waits between two attempts. A port so the tests do not wait. */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;
}
