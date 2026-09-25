package me.piitex.cca.ui.chat;

import me.piitex.engine.utils.Platform;
import org.lwjgl.glfw.GLFW;

// The chat's keyboard shortcuts. The engine only sends keys to the focused element, so these are caught by the message box.
// The letters are ones the text box doesn't use itself (it has A, C, V, X, Y and Z). Cmd on a Mac, Ctrl everywhere else.
enum ChatShortcuts {
    REGENERATE("Regenerate", GLFW.GLFW_KEY_R, true, "R"),
    CONTINUE("Continue", GLFW.GLFW_KEY_K, true, "K"),
    EDIT("Edit", GLFW.GLFW_KEY_E, true, "E"),
    DELETE("Delete", GLFW.GLFW_KEY_D, true, "D"),
    UNDO("Undo response", GLFW.GLFW_KEY_U, true, "U"),
    NEW_CHAT("New chat", GLFW.GLFW_KEY_N, true, "N"),
    PREVIOUS("Previous response", GLFW.GLFW_KEY_LEFT, false, "Alt+Left"),
    NEXT("Next response", GLFW.GLFW_KEY_RIGHT, false, "Alt+Right"),
    STOP("Stop", GLFW.GLFW_KEY_ESCAPE, false, "Esc");

    private final String action;
    private final int key;
    private final boolean command;
    private final String label;

    ChatShortcuts(String action, int key, boolean command, String label) {
        this.action = action;
        this.key = key;
        this.command = command;
        this.label = (command ? commandKey() : "") + label;
    }

    private static String commandKey() {
        return Platform.getCurrent() == Platform.MACOS ? "Cmd+" : "Ctrl+";
    }

    // "Regenerate (Cmd+R)"
    String tooltip() {
        return action + " (" + label + ")";
    }

    String label() {
        return label;
    }

    // Null if the key isn't one. Cmd/Ctrl ones must have nothing else held, Alt and Esc have no Cmd/Ctrl.
    static ChatShortcuts of(int key, int mods) {
        boolean command = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        for (ChatShortcuts shortcut : values()) {
            if (shortcut.key != key || shift) continue;
            if (shortcut.command ? command && !alt : !command && (shortcut == STOP ? !alt : alt)) return shortcut;
        }
        return null;
    }
}
