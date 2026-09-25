package me.piitex.cca.ui.chat;

import me.piitex.cca.model.ChatMessage;
import me.piitex.engine.ui.Element;

// Decides how a single message looks. Everything else on the chat screen is shared and handled by ChatMenu.
// Called on every redraw, so always build new elements.
interface ChatStyle {

    // The whole message as one element, width wide. If actions.editing() is set, show the editor instead of the text.
    // user is null when the character has no user template.
    Element buildMessage(ChatMessage message, Speaker speaker, Speaker user, double width, MessageActions actions);

    // How the text is drawn. The editor uses it too so nothing moves when editing starts.
    MessageBody.Look look();

    // Space between messages.
    double messageGap();
}
