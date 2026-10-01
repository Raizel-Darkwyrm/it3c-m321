package ch.benedict.m321.batchwriter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Stellt den unabhängigen Zeitgeber bereit, der auch während blockierender JDBC-Aufrufe läuft. */
@Configuration
public class DatabaseConfig {

    /** Abgesagte Aufgaben werden entfernt; beim Stop bleiben keine Timer-Threads zurück. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledThreadPoolExecutor databaseTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
