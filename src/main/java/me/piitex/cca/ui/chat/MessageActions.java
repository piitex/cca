package me.piitex.cca.ui.chat;

import java.util.function.IntConsumer;

// What can be done to one message, already bound to it by ChatMenu. The style only decides where to show them.
// A null action isn't available for that message and shouldn't be drawn.
// newest is whether the chat's keyboard shortcuts act on this message, so its buttons can show them.
// edit takes where to put the caret, -1 for the end. regenerate, continueReply, undo and variants are only set on the newest message.
// undo removes the reply and puts the prompt it answered back in the message box, so it's only on a reply that has one.
// branch starts a new chat with this message and everything before it, it's on every message.
record MessageActions(Runnable copy, IntConsumer edit, Runnable regenerate, Runnable continueReply, Runnable undo, Runnable branch, Runnable delete, Variants variants, Editing editing, boolean newest) {

    // Which reply is showing (index starts at 0) and how to flip between them.
    record Variants(int index, int count, Runnable previous, Runnable next) {
    }

    // Set on the message being edited. The style shows this editor instead of the text and save/cancel instead of the buttons.
    record Editing(MessageBody body, Runnable save, Runnable cancel) {
    }

    // For a reply that's still being generated.
    static final MessageActions NONE = new MessageActions(null, null, null, null, null, null, null, null, null, false);

    // Variants only matter when there's more than one.
    Variants shownVariants() {
        return variants != null && variants.count() > 1 ? variants : null;
    }
}
