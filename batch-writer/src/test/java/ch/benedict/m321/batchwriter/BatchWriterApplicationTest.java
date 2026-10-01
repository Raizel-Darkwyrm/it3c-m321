package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft den eigenständigen Anwendungsstart, bevor der Schreibweg ergänzt wird.
 * Der Hintergrunddienst soll dabei keinen HTTP-Server benötigen.
 */
@SpringBootTest(classes = BatchWriterApplication.class)
class BatchWriterApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    /** Der Start muss die Anwendungskonfiguration im Spring-Kontext bereitstellen. */
    @Test
    void startsApplicationContext() {
        BatchWriterApplication application = applicationContext.getBean(BatchWriterApplication.class);

        assertNotNull(application);
    }

    /** Ein normaler Anwendungskontext genügt, weil der Writer keine HTTP-API anbietet. */
    @Test
    void startsWithoutWebApplicationContext() {
        assertInstanceOf(AnnotationConfigApplicationContext.class, applicationContext);
    }
}
