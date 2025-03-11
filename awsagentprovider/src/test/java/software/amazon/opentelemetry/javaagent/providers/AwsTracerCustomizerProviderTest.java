package software.amazon.opentelemetry.javaagent.providers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

class AwsTracerCustomizerProviderTest {

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        // Backup original system properties
        backupProperty("otel.aws.application.signals.enabled");
        backupProperty("otel.metrics.exporter");
        backupProperty("otel.logs.export");
        backupProperty("otel.aws.application.signals.exporter.endpoint");
        backupProperty("otel.exporter.otlp.protocol");
        backupProperty("otel.exporter.otlp.traces.endpoint");
        backupProperty("otel.traces.sampler");
        backupProperty("otel.traces.sampler.arg");
    }

    private void backupProperty(String key) {
        if (System.getProperty(key) != null) {
            originalProperties.put(key, System.getProperty(key));
        }
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void testSetSystemPropertyFromEnvOrDefault_AllValuesSet() {
        System.setProperty("otel.aws.application.signals.enabled", "false");
        assertEquals("false", System.getProperty("otel.aws.application.signals.enabled"));
        assertNull(System.getProperty("otel.metrics.exporter"));
        assertNull(System.getProperty("otel.logs.export"));
        assertNull(System.getProperty("otel.aws.application.signals.exporter.endpoint"));
        assertNull(System.getProperty("otel.exporter.otlp.protocol"));
        assertNull(System.getProperty("otel.exporter.otlp.traces.endpoint"));
        assertNull(System.getProperty("otel.traces.sampler"));
        assertNull(System.getProperty("otel.traces.sampler.arg"));
    }
}
