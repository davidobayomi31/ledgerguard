package dev.obayomi.ledgerguard.transfer;

/** Phase 2 adds HELD (waiting for fraud review) between request and SETTLED. */
public enum TransferStatus { SETTLED, REJECTED }
