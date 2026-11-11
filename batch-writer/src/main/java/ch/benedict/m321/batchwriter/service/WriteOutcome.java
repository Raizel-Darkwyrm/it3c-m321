package ch.benedict.m321.batchwriter.service;

/** Trennt bestätigte Speicherung, Duplikate, Datenfehler und erneut zustellbare Lieferungen. */
public enum WriteOutcome {
    STORED,
    DUPLICATE,
    INVALID,
    RETRY
}
