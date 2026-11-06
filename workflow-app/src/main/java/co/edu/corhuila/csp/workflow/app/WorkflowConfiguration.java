package co.edu.corhuila.csp.workflow.app;

import co.edu.corhuila.csp.workflow.adapter.in.http.AuthFilter;
import co.edu.corhuila.csp.workflow.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.csp.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.csp.workflow.adapter.out.participants.HttpBookingParticipant;
import co.edu.corhuila.csp.workflow.adapter.out.participants.ServiceToken;
import co.edu.corhuila.csp.workflow.adapter.out.state.InMemorySagaStore;
import co.edu.corhuila.csp.workflow.adapter.out.state.JdbcSagaStore;
import co.edu.corhuila.csp.workflow.application.port.out.BookingParticipant;
import co.edu.corhuila.csp.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.csp.workflow.application.port.out.SagaStore;
import co.edu.corhuila.csp.workflow.application.usecase.RunBookSeatsSaga;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** The only place that knows every adapter. Every limit, timeout and retry is explicit here and in the configuration. */
@Configuration
class WorkflowConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    /** The state of the sagas lives in PostgreSQL (ADR-065); the service does not start without its database. */
    @Bean
    @ConditionalOnProperty(name = "workflow.state.store", havingValue = "postgres", matchIfMissing = true)
    SagaStore postgresSagaStore(DataSource dataSource) {
        return new JdbcSagaStore(dataSource);
    }

    /** Only for the tests of the service that run without a database: a restart loses every saga. */
    @Bean
    @ConditionalOnProperty(name = "workflow.state.store", havingValue = "memory")
    SagaStore memorySagaStore() {
        return new InMemorySagaStore();
    }

    @Bean
    ServiceToken serviceToken(@Value("${SERVICE_TOKEN:}") String token, @Value("${SERVICE_TOKEN_FILE:}") String file) {
        return new ServiceToken(token, file);
    }

    @Bean
    BookingParticipant bookingParticipant(@Value("${workflow.booking-url}") String bookingUrl, ServiceToken token,
                                          @Value("${workflow.http.timeout-seconds}") long timeoutSeconds,
                                          @Value("${workflow.http.attempts}") int attempts,
                                          @Value("${workflow.http.backoff-ms}") long backoffMillis) {
        return new HttpBookingParticipant(bookingUrl, token::current, Duration.ofSeconds(timeoutSeconds), attempts,
                Duration.ofMillis(backoffMillis), Thread::sleep);
    }

    @Bean
    RunBookSeatsSaga bookSeatsSaga(BookingParticipant booking, SagaStore store, IdGenerator ids, Clock clock) {
        return new RunBookSeatsSaga(booking, store, ids, clock);
    }

    @Bean
    SagaRecoveryRunner sagaRecoveryRunner(RunBookSeatsSaga bookSeatsSaga, Clock clock) {
        return new SagaRecoveryRunner(bookSeatsSaga, clock);
    }

    /** JWT_PUBLIC_KEY (one line with literal \n escapes is accepted) or JWT_PUBLIC_KEY_FILE. */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem, @Value("${JWT_PUBLIC_KEY_FILE:}") String file)
            throws IOException {
        String key = !pem.isBlank() ? pem.replace("\\n", "\n") : !file.isBlank() ? Files.readString(Path.of(file)) : "";
        if (key.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE is required: the service validates the token itself");
        }
        return new Rs256Verifier(key);
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json, Clock clock) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json, clock));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
