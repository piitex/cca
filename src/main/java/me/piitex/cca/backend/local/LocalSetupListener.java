package me.piitex.cca.backend.local;

// Both of these are called from the setup thread, NOT the render thread. Don't touch the UI from here.
public interface LocalSetupListener {

    default void onStageChanged(LocalSetupStage stage) {
    }

    // Called once at the very end.
    default void onFinished(boolean success) {
    }
}
