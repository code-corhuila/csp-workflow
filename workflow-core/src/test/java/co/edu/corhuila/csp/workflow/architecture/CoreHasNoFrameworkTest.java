package co.edu.corhuila.csp.workflow.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ADR-064, criterion 1: the domain and the application of the workflow do not depend on Spring (Annex C). */
class CoreHasNoFrameworkTest {

    @Test
    void thePomOfTheCoreDoesNotDeclareSpring() throws IOException {
        assertFalse(Files.readString(Path.of("pom.xml")).contains("org.springframework"));
    }

    @Test
    void noSourceOfTheCoreImportsSpring() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src"))) {
            boolean importsSpring = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("CoreHasNoFrameworkTest.java"))
                    .anyMatch(path -> contains(path, "import org.springframework"));
            assertFalse(importsSpring);
        }
    }

    private static boolean contains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
