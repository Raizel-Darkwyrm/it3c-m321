package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des eigenständigen Hintergrunddienstes.
 * Der Schreibweg wird später ergänzt; dieser Rahmen benötigt keine HTTP-API.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /** Startet den Spring-Kontext für die späteren Komponenten des Schreibwegs. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
