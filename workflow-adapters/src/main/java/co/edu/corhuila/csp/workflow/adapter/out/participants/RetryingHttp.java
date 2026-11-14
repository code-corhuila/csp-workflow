package co.edu.corhuila.csp.workflow.adapter.out.participants;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a request to a participant and retries only what a retry can fix: the network, 429 and 5xx, with
 * exponential backoff and full jitter and a bounded number of attempts (norm 5.3.10, Annex D). Any other status is
 * the answer of the participant and is returned as it is. The calls carry an idempotency key, so a retry is safe.
 */
final class RetryingHttp {

    private static final Logger log = LoggerFactory.getLogger(RetryingHttp.class);

    record Reply(int status, String body) { }

    private final HttpClient client;
    private final int attempts;
    private final Duration backoffBase;
    private final Sleeper sleeper;

    RetryingHttp(HttpClient client, int attempts, Duration backoffBase, Sleeper sleeper) {
        this.client = client;
        this.attempts = attempts;
        this.backoffBase = backoffBase;
        this.sleeper = sleeper;
    }

    /** @throws IOException when the network failed on the last attempt */
    Reply send(HttpRequest request) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (!retryable(status) || attempt == attempts) {
                    return new Reply(status, response.body());
                }
                backOff(attempt, "status " + status);
            } catch (IOException e) {
                if (attempt == attempts) {
                    throw e;
                }
                backOff(attempt, "network error");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while calling a participant", e);
            }
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    /** Full jitter: a random wait between 0 and {@code base * 2^attempt}. */
    private void backOff(int attempt, String reason) throws IOException {
        long ceiling = backoffBase.toMillis() * (1L << attempt);
        long wait = ThreadLocalRandom.current().nextLong(ceiling + 1);
        log.warn("participant call failed ({}), attempt {}/{}, retrying in {} ms", reason, attempt, attempts, wait);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting to retry", e);
        }
    }
}
