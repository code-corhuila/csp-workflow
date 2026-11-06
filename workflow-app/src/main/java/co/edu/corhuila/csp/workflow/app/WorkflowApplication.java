package co.edu.corhuila.csp.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Composition root of the workflow. */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.csp.workflow")
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
