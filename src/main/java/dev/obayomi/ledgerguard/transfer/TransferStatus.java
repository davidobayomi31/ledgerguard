package dev.obayomi.ledgerguard.transfer;

/**
 * SETTLED: money moved. REJECTED: refused automatically (e.g. insufficient funds).
 * HELD: flagged by the fraud rules, waiting for an analyst; no money has moved.
 * BLOCKED: an analyst stopped it; no money moved.
 */
public enum TransferStatus { SETTLED, REJECTED, HELD, BLOCKED }
