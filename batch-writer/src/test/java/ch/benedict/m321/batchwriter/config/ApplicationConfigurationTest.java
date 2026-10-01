package ch.benedict.m321.batchwriter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Prüft die echte YAML-Datei mit kontrollierten Variablen unabhängig von lokaler .env. */
class ApplicationConfigurationTest {

    /** Pool und Treiber brauchen eigene Grenzen, bevor überhaupt eine SQL-Transaktion läuft. */
    @Test
    void limitsConnectionAndSocketWaits() throws IOException {
        Map<String, Object> variables = testVariables();
        PropertySourcesPropertyResolver properties = loadProperties(variables);
        assertEquals("1000", properties.getProperty("spring.datasource.hikari.connection-timeout"));
        assertEquals("500", properties.getProperty("spring.datasource.hikari.validation-timeout"));
        assertEquals("-1", properties.getProperty("spring.datasource.hikari.initialization-fail-timeout"));
        assertEquals("1", properties.getProperty("spring.datasource.hikari.data-source-properties.connectTimeout"));
        assertEquals("1", properties.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"));
        assertEquals("1", properties.getProperty("spring.datasource.hikari.data-source-properties.cancelSignalTimeout"));
    }

    /** Andere Werte als die Compose-Beispiele decken fest eingetragene Zugangsdaten auf. */
    @Test
    void resolvesDatabaseVariables() throws IOException {
        Map<String, Object> variables = testVariables();
        PropertySourcesPropertyResolver properties = loadProperties(variables);
        assertEquals("jdbc:postgresql://database-test:5432/messages_test",
                properties.getProperty("spring.datasource.url"));
        assertEquals("writer_test", properties.getProperty("spring.datasource.username"));
        assertEquals("database-test-password", properties.getProperty("spring.datasource.password"));
    }

    /** Broker-Adresse und Zugangsdaten müssen aus den vereinbarten Variablen stammen. */
    @Test
    void resolvesRabbitVariables() throws IOException {
        Map<String, Object> variables = testVariables();
        PropertySourcesPropertyResolver properties = loadProperties(variables);
        assertEquals("broker-test", properties.getProperty("spring.rabbitmq.host"));
        assertEquals("5672", properties.getProperty("spring.rabbitmq.port"));
        assertEquals("/", properties.getProperty("spring.rabbitmq.virtual-host"));
        assertEquals("publisher_test", properties.getProperty("spring.rabbitmq.username"));
        assertEquals("broker-test-password", properties.getProperty("spring.rabbitmq.password"));
    }

    /** Die Anwendung ist ein Hintergrunddienst und darf kein eigenes Schema initialisieren. */
    @Test
    void disablesWebServerAndSchemaInitialization() throws IOException {
        Map<String, Object> variables = testVariables();
        PropertySourcesPropertyResolver properties = loadProperties(variables);
        assertEquals("batch-writer", properties.getProperty("spring.application.name"));
        assertEquals("none", properties.getProperty("spring.main.web-application-type"));
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
    }

    /** Fehlende Passwörter dürfen nicht durch versteckte Standardpasswörter ersetzt werden. */
    @Test
    void requiresPasswordsFromEnvironment() throws IOException {
        Map<String, Object> variables = testVariables();
        variables.remove("POSTGRES_PASSWORD");
        variables.remove("RABBITMQ_PASSWORD");
        PropertySourcesPropertyResolver properties = loadProperties(variables);
        assertThrows(IllegalArgumentException.class, () -> properties.getProperty("spring.datasource.password"));
        assertThrows(IllegalArgumentException.class, () -> properties.getProperty("spring.rabbitmq.password"));
    }

    /** Nur Testwerte werden bereitgestellt; Betriebssystemvariablen beeinflussen das Ergebnis nicht. */
    private Map<String, Object> testVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("POSTGRES_HOST", "database-test");
        variables.put("POSTGRES_DB", "messages_test");
        variables.put("POSTGRES_USER", "writer_test");
        variables.put("POSTGRES_PASSWORD", "database-test-password");
        variables.put("RABBITMQ_HOST", "broker-test");
        variables.put("RABBITMQ_USER", "publisher_test");
        variables.put("RABBITMQ_PASSWORD", "broker-test-password");
        return variables;
    }

    /** Spring liest die produktive Datei; es gibt keine abweichende Testkopie der YAML. */
    private PropertySourcesPropertyResolver loadProperties(Map<String, Object> variables) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        ClassPathResource resource = new ClassPathResource("application.yml");
        List<PropertySource<?>> configuration = loader.load("writerConfiguration", resource);
        MutablePropertySources sources = new MutablePropertySources();
        MapPropertySource environment = new MapPropertySource("testEnvironment", variables);
        sources.addFirst(environment);
        for (PropertySource<?> source : configuration) {
            sources.addLast(source);
        }
        return new PropertySourcesPropertyResolver(sources);
    }
}
