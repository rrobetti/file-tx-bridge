package com.example.filetxbridge.core;

public enum TxState {
    ACTIVE,
    ENDED,
    PREPARED,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK
}
