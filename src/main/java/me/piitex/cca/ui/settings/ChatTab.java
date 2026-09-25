package me.piitex.cca.ui.settings;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.chat.AvatarShape;
import me.piitex.cca.ui.chat.ChatPreview;
import me.piitex.cca.ui.chat.ChatStyleType;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;

import java.util.Arrays;

// The Chat tab. A live preview in a column on the left and the options beside it. Numbers, colors and pictures only
// restyle the preview, toggles and dropdowns that change which rows exist redraw the screen.
final class ChatTab {
    private static final double GAP = 16;

    private final AppearanceSettings draft;
    private final ScrollMemory scroll;
    private final Runnable changed;
    private final Runnable redraw;

    private ChatPreview preview;

    ChatTab(AppearanceSettings draft, ScrollMemory scroll, Runnable changed, Runnable redraw) {
        this.draft = draft;
        this.scroll = scroll;
        this.changed = changed;
        this.redraw = redraw;
    }

    // panelWidth and panelHeight are the size the chat window will have.
    Element build(double width, double height, double panelWidth, double panelHeight) {
        Container holder = Components.transparent(width, height);
        preview = new ChatPreview(height, panelWidth, panelHeight);
        preview.show(draft);
        preview.element().setPosition(0, 0);
        holder.addElement(preview.element());

        double formX = ChatPreview.WIDTH + GAP;
        SettingsForm f = new SettingsForm(Math.max(0, width - formX), height, scroll);
        f.note("The preview scrolls. Once you pick a picture, the small box on top shows the whole window. Changes apply when you save.");
        style(f);
        window(f);
        boolean roleplay = ChatStyleType.fromId(draft.chatStyle.get()) == ChatStyleType.ROLEPLAY;
        if (roleplay) roleplayText(f);
        avatars(f, roleplay);
        Element form = f.finish();
        form.setPosition(formX, 0);
        holder.addElement(form);
        return holder;
    }

    private void style(SettingsForm f) {
        f.section("Chat style");
        f.row("Style", ChatStyleType.fromId(draft.chatStyle.get()).getBlurb(), SettingsUi.choice(draft.chatStyle,
                Arrays.stream(ChatStyleType.values()).map(ChatStyleType::getId).toList(),
                id -> ChatStyleType.fromId(id).getDisplayName(), this::restructured));
        f.endCard();
    }

    private void window(SettingsForm f) {
        new ImageSection(draft.chatImage, "chat window", () -> preview.image(), this::edited, this::restructured)
                .rows(f, "Chat window picture");
        f.endCard();
    }

    private void roleplayText(SettingsForm f) {
        f.section("Roleplay text");
        f.row("Quotes", "Text inside \"quotation marks\", the words a character says.", SettingsUi.color(draft.dialogueColor, false, this::edited));
        f.row("Actions", "Text inside *asterisks*, what a character does.", SettingsUi.color(draft.actionColor, false, this::edited));
        f.endCard();
    }

    private void avatars(SettingsForm f, boolean roleplay) {
        f.section("Avatars");
        f.row("Show avatars", "Pictures next to messages.", SettingsUi.toggle(draft.avatarShow, this::restructured));
        if (draft.avatarShow.get()) {
            f.row("Show yours", "Also show a picture next to your own messages.", SettingsUi.toggle(draft.avatarShowUser, this::edited));
            f.row("Shape", "How avatars are cut.", SettingsUi.choice(draft.avatarShape,
                    Arrays.stream(AvatarShape.values()).map(AvatarShape::getId).toList(),
                    id -> AvatarShape.fromId(id).getLabel(), this::edited));
            if (roleplay) {
                f.row("Portrait width", "How wide the character's picture is on each message.", SettingsUi.intSpinner(draft.portraitWidth, 16, 600, 1, this::edited));
                f.row("Portrait height", "How tall the character's picture is on each message.", SettingsUi.intSpinner(draft.portraitHeight, 16, 600, 1, this::edited));
            } else {
                f.row("Width", "How wide an avatar is. Type any size.", SettingsUi.intSpinner(draft.avatarWidth, 16, 256, 1, this::edited));
                f.row("Height", "How tall an avatar is. Type any size.", SettingsUi.intSpinner(draft.avatarHeight, 16, 256, 1, this::edited));
            }
            new ImageSection(draft.avatarImage, "avatar", () -> null, this::edited, this::restructured).layout(f, "Avatar picture");
        }
        f.endCard();
    }

    private void edited() {
        preview.show(draft);
        changed.run();
    }

    private void restructured() {
        changed.run();
        redraw.run();
    }
}
