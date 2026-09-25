package me.piitex.cca.backend.local;

// The steps of local setup in order. FAILED can happen after any of them.
public enum LocalSetupStage {
    IDLE,
    DOWNLOADING_BACKEND,
    DOWNLOADING_MODEL,
    STARTING_SERVER,
    VERIFYING_SERVER,
    SUCCESS,
    FAILED
}
