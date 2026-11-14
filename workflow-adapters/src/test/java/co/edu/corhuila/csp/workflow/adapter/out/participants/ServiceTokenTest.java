package co.edu.corhuila.csp.workflow.adapter.out.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTokenTest {

    @Test
    void aFixedTokenIsStripped() {
        assertEquals("abc", new ServiceToken(" abc \n", "").current());
    }

    @Test
    void aTokenFileIsReadAgainOnEveryCall(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("token");
        Files.writeString(file, "first\n");
        ServiceToken token = new ServiceToken("", file.toString());

        assertEquals("first", token.current());
        Files.writeString(file, "second\n");

        assertEquals("second", token.current());
    }

    @Test
    void theFileWinsOverTheFixedToken(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("token");
        Files.writeString(file, "from-file");

        assertEquals("from-file", new ServiceToken("fixed", file.toString()).current());
    }

    @Test
    void withoutATokenTheServiceRefusesToStart() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ServiceToken("", ""));

        assertEquals("SERVICE_TOKEN or SERVICE_TOKEN_FILE is required: the participants reject an unauthenticated request",
                failure.getMessage());
    }

    @Test
    void anUnreadableFileRefusesToStart(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> new ServiceToken("", dir.resolve("missing").toString()));
    }
}
