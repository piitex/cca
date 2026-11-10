package me.piitex.cca.ui.chat;

import me.piitex.cca.App;
import me.piitex.cca.backend.server.ChatCompletion;
import me.piitex.cca.backend.server.PromptBuilder;
import me.piitex.cca.backend.server.DialogueFormat;
import me.piitex.cca.backend.server.ServerProcess;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.ImageOptions;
import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.crypto.ProtectedImages;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.Chat;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.ContentMenu;
import me.piitex.cca.ui.GrammarChecker;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.*;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;
import org.jetbrains.skia.Image;
import org.json.JSONArray;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The chat with a character. Opens their most recent chat, or starts a new one with their first message.
 * How each message looks is up to the ChatStyle picked in Settings > Chat, everything else here is shared.
 * <p>
 * The reply streams into a pending message at the bottom that's redrawn in place as text comes in, and
 * it's only added to the chat once it's done (or stopped, keeping what arrived).
 */
public class ChatMenu implements ContentMenu {
    private final Theme theme = Components.theme();
    private final MainMenu owner;
    private final Character character;
    // Null when the character has no user set.
    private final Speaker userSpeaker;
    private Chat chat;

    // Read once per chat, so changing the style applies next time a chat is opened.
    private final ChatStyle style = ChatStyleType.fromId(App.instance.getAppearance().chatStyle.get()).create(App.instance.getAppearance());
    private final ImageBackground windowImage = new ImageBackground();

    private static final double PANEL_PADDING = 22;
    private static final double TOOLBAR_HEIGHT = 36;
    private static final double NEW_CHAT_WIDTH = 130;
    private static final double ASK_TOGGLE_WIDTH = 36;
    private static final double COMPOSER_HEIGHT = 100;
    private static final double SEND_SIZE = 44;
    private static final double GROUP_BAR_HEIGHT = 32;
    // For this run of the app only, so a restart asks again. Shared by every chat.
    private static boolean confirmMessageDeletes = true;
    private static final DateTimeFormatter CHAT_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CHAT_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");

    // Kept between redraws so a half typed message isn't lost when the window is resized.
    private final RichTextAreaOverlay input;

    // The list from the last redraw and how many messages it had. Used to tell a new message (scroll to
    // the bottom) from an edit or delete (stay where they are).
    private ScrollContainer messageList;
    private int renderedMessageCount;

    // Opening a chat lays its messages out a slice per frame, see prepare. The chat and width that's done for, and the
    // progress of the one in flight.
    private static final long FIRST_SLICE_NANOS = 12_000_000L;
    private static final long SLICE_NANOS = 10_000_000L;
    private Chat preparedChat;
    private double preparedWidth = -1;
    private int loadToken;
    private List<ChatMessage> loadMessages;
    private int loadCursor;
    private ProgressBarOverlay loadBar;

    // The reply being generated, only one at a time. pendingReply is drawn at the end of generatingChat
    // but isn't part of it until finishReply adds it. pendingRow is swapped in place on every update.
    private ChatCompletion generation;
    private Chat generatingChat;
    private ChatMessage pendingReply;
    // Who the reply being generated is from. Only differs from the chat's character in a group chat.
    private Character generatingSpeaker;
    // In a group chat, who the user picked to reply next. Null means the next one in turn, see nextSpeaker.
    private Character pickedSpeaker;
    private Element pendingRow;
    private double pendingRowY;
    private double pendingRowWidth;
    private String generationError;

    // The message being edited and its editor. The editor is kept between redraws like the input so the
    // caret, undo and focus aren't lost.
    private ChatMessage editingMessage;
    private MessageBody editBody;

    // The message a regenerate is adding another reply to. It stays in the chat but is hidden while the
    // new reply streams in its place. Null when the reply is a new message.
    private ChatMessage regeneratingMessage;

    // The newest reply while it's being continued and the text it had. It stays in the history so the model sees it,
    // and is hidden like a regenerating one while the longer version streams in its place.
    private ChatMessage continuingMessage;
    private String continuingText = "";
    private boolean closed;

    // Progress comes in on the request thread for every token. Only the latest text matters so it's parked
    // here and at most one update is queued for the render thread.
    private final AtomicBoolean progressQueued = new AtomicBoolean();
    private volatile String latestContent = "";
    private volatile String latestReasoning = "";

    public ChatMenu(MainMenu owner, Character character) {
        this.owner = owner;
        this.character = character;
        this.userSpeaker = Speaker.userOf(character);

        // Chats are named by when they were created and loaded in name order, so the last one is the newest.
        List<Chat> chats = character.getChats();
        chat = chats.isEmpty() ? createChat() : chats.getLast();

        input = new ComposerArea(COMPOSER_HEIGHT, this::send, this::shortcut);
        input.setPlaceholder("Message " + chatName() + "...");
        Components.styleTextArea(input);
        allowNames();

        // Decrypting a big picture takes a while, read it now so the first draw finds it ready.
        Path background = character.getBackgroundFile();
        if (background != null) ProtectedImages.preload(List.of(background));

        // Loading a model takes a while, start now so it's usually ready by the first message.
        if (!App.instance.getModelSettings().usesCloud()) {
            ServerProcess.get().start();
        }
    }

    // Stops a reply that's generating (what came in is still saved).
    @Override
    public void close() {
        closed = true;
        endEdit();
        if (generation != null) generation.cancel();
    }

    @Override
    public String getTitle() {
        return chatName();
    }

    private String displayName() {
        return nameOf(character);
    }

    private static String nameOf(Character who) {
        String name = who.getDisplayName();
        return name.isBlank() ? "Unnamed" : name;
    }

    // A character in a selection (the member picker and the reply dropdown). The id, since copies of one character
    // share a display name.
    private static String idOf(Character who) {
        return who.getId();
    }

    // "Alice", or "Alice, Bob and Carol" in a group chat. The chat itself goes by display names.
    private String chatName() {
        List<Character> participants = chat.getParticipants();
        if (participants.size() == 1) return displayName();
        List<String> names = participants.stream().map(ChatMenu::nameOf).toList();
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    // Their names would be flagged as misspelled otherwise.
    private void allowNames() {
        GrammarChecker checker = GrammarChecker.shared();
        if (checker == null) return;
        checker.allow(character.getDisplayName(), character.getUserName());
        for (Character member : chat.getMembers()) checker.allow(member.getDisplayName());
    }

    // The character a message is from. The user's messages count as the chat's own character, they have no speaker.
    private Character authorOf(ChatMessage message) {
        Character author = message.getCharacter();
        return author == null ? character : author;
    }

    @Override
    public Element render(double width, double height) {
        Container panel = Components.panel(width, height);
        configureWindowImage();
        windowImage.setCornerRadius(Components.PANEL_RADIUS);
        panel.setBackground(windowImage);
        double innerWidth = Math.max(0, width - 2 * PANEL_PADDING);

        Element toolbar = buildToolbar(innerWidth);
        toolbar.setPosition(PANEL_PADDING, PANEL_PADDING);
        panel.addElement(toolbar);

        SeparatorOverlay divider = new SeparatorOverlay(innerWidth);
        divider.setLineColor(Palette.border());
        divider.setPosition(PANEL_PADDING, PANEL_PADDING + TOOLBAR_HEIGHT + 14);
        panel.addElement(divider);
        double listY = PANEL_PADDING + TOOLBAR_HEIGHT + 14 + divider.getHeight() + 16;

        TextOverlay hint = generationError != null
                ? new TextOverlay(generationError, 11f, Color.CRIMSON)
                : new TextOverlay("Enter to send · Shift+Enter for a new line · Hover a button for its shortcut", 11f, Palette.muted());
        double composerY = height - PANEL_PADDING - hint.getHeight() - 8 - COMPOSER_HEIGHT;
        // A group chat has a bar above the message box to pick who replies.
        boolean group = chat.isGroup();
        double groupBarSpace = group ? GROUP_BAR_HEIGHT + 8 : 0;
        double listHeight = Math.max(0, composerY - listY - 16 - groupBarSpace);
        input.setPlaceholder("Message " + chatName() + "...");

        Element messages;
        if (chat.getMessages().isEmpty() && !isShowingPendingReply()) {
            messages = Components.emptyState(innerWidth, listHeight, Feather.MESSAGE_CIRCLE,
                    "Start a conversation with " + chatName(), "Your messages are saved encrypted on this device.");
            messageList = null; // The next message starts a fresh list at the bottom.
        } else if (!prepare(rowWidthFor(innerWidth))) {
            messages = buildLoader(innerWidth, listHeight);
            messageList = null; // Open at the newest message once it's loaded.
        } else {
            messages = buildMessageList(innerWidth, listHeight);
        }
        messages.setPosition(PANEL_PADDING, listY);
        panel.addElement(messages);

        if (group) {
            Element bar = buildGroupBar(innerWidth);
            bar.setPosition(PANEL_PADDING, composerY - 8 - GROUP_BAR_HEIGHT);
            panel.addElement(bar);
        }

        double inputWidth = Math.max(0, innerWidth - SEND_SIZE - 12);
        input.setWidth(inputWidth);
        input.setHeight(COMPOSER_HEIGHT);
        input.setPosition(PANEL_PADDING, composerY);
        panel.addElement(input);

        // The send button turns into a stop button while a reply is generating.
        Container sendButton = generation != null
                ? Components.roundButton(Feather.SQUARE, ChatShortcuts.STOP.tooltip(), SEND_SIZE, 16, Components.CONTROL_DANGER, Components.DANGER_HOVER, this::stop)
                : Components.roundButton(Feather.SEND, "Send (Enter)", SEND_SIZE, 18, Palette.accent(), Palette.accentHover(), this::send);
        sendButton.setPosition(PANEL_PADDING + inputWidth + 12, composerY + COMPOSER_HEIGHT - SEND_SIZE);
        panel.addElement(sendButton);

        double footerY = composerY + COMPOSER_HEIGHT + 8;
        hint.setPosition(PANEL_PADDING, footerY);
        panel.addElement(hint);

        // Checked every frame since the server finishes loading on its own time.
        double statusRight = PANEL_PADDING + innerWidth;
        TextOverlay status = new TextOverlay(modelStatus(), 11f, Palette.muted());
        status.setPosition(statusRight - status.getWidth(), footerY);
        status.onUpdate(event -> {
            String text = modelStatus();
            if (!text.equals(status.getText())) {
                status.setText(text);
                status.setPosition(statusRight - status.getWidth(), footerY);
            }
        });
        panel.addElement(status);

        Scheduler.runLater(this::focusComposer);

        return panel;
    }

    // The character's own picture when it has one, laid out with the look from Settings > Chat. Otherwise the picture from there.
    private void configureWindowImage() {
        ImageOptions options = App.instance.getAppearance().chatImage;
        Path own = character.getBackgroundFile();
        Image image = own == null ? null : ProtectedImages.load(own);
        if (image != null) {
            Backgrounds.configure(windowImage, options, image);
        } else {
            Backgrounds.configure(windowImage, options);
        }
    }

    // Shortcuts only reach the message box while it has focus, and clicking a button or closing a dialog takes that away.
    private void focusComposer() {
        boolean free = App.window.getFocusedElement() == null && App.window.getModal() == null && App.window.getPopup() == null;
        if (free && !closed && editBody == null) App.window.requestFocus(input);
    }

    private static String modelStatus() {
        ModelSettings settings = App.instance.getModelSettings();
        if (settings.usesCloud()) {
            String name = settings.cloudModel.get().trim();
            return name.isEmpty() ? "Cloud endpoint" : "Cloud: " + name;
        }
        ServerProcess server = ServerProcess.get();
        return switch (server.getState()) {
            case STARTING -> "Loading model...";
            case READY -> server.getModelFile() != null ? "Model: " + stripExtension(server.getModelFile().getFileName().toString()) : "Model ready";
            case FAILED -> "Model failed to load";
            case STOPPED -> "Model stopped";
        };
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // Chat picker (newest first), rename and delete for the selected chat, and New Chat.
    private Element buildToolbar(double width) {
        Container toolbar = Components.transparent(width, TOOLBAR_HEIGHT);

        double buttonWidth = Math.min(NEW_CHAT_WIDTH, width);
        double controlSize = Components.CONTROL_SIZE, controlGap = 6;
        double controlsWidth = 3 * controlSize + 2 * controlGap;
        TextOverlay askLabel = new TextOverlay("Confirm deletes", 12f, Palette.muted());
        double askWidth = ASK_TOGGLE_WIDTH + 8 + askLabel.getWidth();
        double dropdownWidth = Math.max(0, Math.min(340, width - buttonWidth - controlsWidth - askWidth - 3 * 12));

        DropdownOverlay<Chat> chatPicker = new DropdownOverlay<>(dropdownWidth, TOOLBAR_HEIGHT);
        chatPicker.setFontSize(14f);
        chatPicker.setLabelProvider(ChatMenu::chatLabel);
        chatPicker.setItems(character.getChats().reversed());
        chatPicker.setSelected(chat);
        chatPicker.onSelect(target -> {
            // switchTo redraws, which removes the dropdown, after that it can no longer close its own list.
            chatPicker.close();
            switchTo(target);
        });
        chatPicker.setPosition(0, 0);
        toolbar.addElement(chatPicker);

        double controlsX = dropdownWidth + 12;
        double controlsY = (TOOLBAR_HEIGHT - controlSize) / 2.0;
        Container rename = Components.iconButton(Feather.EDIT, "Rename chat", Components.CONTROL_EDIT, Components.CONTROL_EDIT_HOVER, this::renameChat);
        rename.setPosition(controlsX, controlsY);
        toolbar.addElement(rename);

        Container members = Components.iconButton(Feather.USERS, "Group members", Components.CONTROL_COPY, Components.CONTROL_COPY_HOVER, this::editMembers);
        members.setPosition(controlsX + controlSize + controlGap, controlsY);
        toolbar.addElement(members);

        Container delete = Components.iconButton(Feather.TRASH, "Delete chat", Components.CONTROL_DANGER, Components.CONTROL_DANGER_HOVER, this::confirmDeleteChat);
        delete.setPosition(controlsX + 2 * (controlSize + controlGap), controlsY);
        toolbar.addElement(delete);

        // Turns the "delete this message?" prompt on and off for this session, the same as the box in the prompt.
        double askX = controlsX + controlsWidth + 12;
        ToggleSwitchOverlay ask = new ToggleSwitchOverlay(ASK_TOGGLE_WIDTH, 20, confirmMessageDeletes);
        ask.setOnColor(Palette.accent());
        ask.onToggle(on -> confirmMessageDeletes = on);
        ask.setPosition(askX, (TOOLBAR_HEIGHT - 20) / 2.0);
        toolbar.addElement(ask);
        askLabel.setPosition(askX + ASK_TOGGLE_WIDTH + 8, (TOOLBAR_HEIGHT - askLabel.getHeight()) / 2.0);
        toolbar.addElement(askLabel);

        ButtonOverlay newChat = Components.accentButton("+ New Chat", 13f, buttonWidth, TOOLBAR_HEIGHT);
        newChat.setPosition(width - buttonWidth, 0);
        newChat.onAction(() -> switchTo(createChat()));
        newChat.setTooltip(ChatShortcuts.NEW_CHAT.tooltip());
        toolbar.addElement(newChat);

        return toolbar;
    }

    // The draft in the composer stays either way. A reply that's generating keeps going for its own chat.
    private void switchTo(Chat target) {
        if (target == null || target == chat) return;
        endEdit();
        chat = target;
        pickedSpeaker = null;
        allowNames();
        messageList = null; // Different chat, open it at the newest message.
        owner.renderContent();
    }

    // Who joins the chat's character in this chat. Nobody picked is an ordinary chat with one character.
    private void editMembers() {
        List<Character> candidates = new ArrayList<>();
        for (Character other : Character.getCharacters().values()) {
            if (!other.getId().equals(character.getId())) candidates.add(other);
        }
        if (candidates.isEmpty()) {
            Components.notice("No other characters", "Create another character first, then add it here to chat with both of them at once.");
            return;
        }
        candidates.sort(Comparator.comparing(candidate -> idOf(candidate).toLowerCase()));

        Chat target = chat;
        List<Character> current = target.getMembers();
        List<String> labels = new ArrayList<>();
        boolean[] initial = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels.add(idOf(candidates.get(i)));
            initial[i] = containsCharacter(current, candidates.get(i));
        }
        Components.pickMany("Group members", "Pick who joins " + displayName() + " in this chat. Leave everyone unticked for a chat with just one character.",
                labels, initial, "Save", ticked -> {
                    List<Character> picked = new ArrayList<>();
                    for (int i = 0; i < ticked.length; i++) {
                        if (ticked[i]) picked.add(candidates.get(i));
                    }
                    target.setMembers(picked);
                    if (target == chat) {
                        pickedSpeaker = null;
                        allowNames();
                    }
                    owner.renderContent();
                }, () -> Scheduler.runLater(this::focusComposer));
    }

    private static boolean containsCharacter(List<Character> characters, Character who) {
        for (Character candidate : characters) {
            if (candidate.getId().equals(who.getId())) return true;
        }
        return false;
    }

    // Who replies next in a group chat: the one the user picked, otherwise the next in turn after whoever wrote the last reply.
    private Character nextSpeaker() {
        List<Character> participants = chat.getParticipants();
        if (pickedSpeaker != null && containsCharacter(participants, pickedSpeaker)) {
            for (Character participant : participants) {
                if (participant.getId().equals(pickedSpeaker.getId())) return participant;
            }
        }
        List<ChatMessage> messages = chat.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getSender() != Role.ASSISTANT) continue;
            String lastId = authorOf(messages.get(i)).getId();
            for (int p = 0; p < participants.size(); p++) {
                if (participants.get(p).getId().equals(lastId)) return participants.get((p + 1) % participants.size());
            }
            break;
        }
        return participants.getFirst();
    }

    // Above the message box in a group chat: who replies, and a button to make them reply without a message from the user.
    private Element buildGroupBar(double width) {
        Container bar = Components.transparent(width, GROUP_BAR_HEIGHT);

        TextOverlay label = new TextOverlay("Reply as", 12f, Palette.muted());
        label.setPosition(0, (GROUP_BAR_HEIGHT - label.getHeight()) / 2.0);
        bar.addElement(label);

        double pickerX = label.getWidth() + 10;
        double replyWidth = 150;
        double pickerWidth = Math.max(0, Math.min(220, width - pickerX - replyWidth - 12));
        DropdownOverlay<Character> picker = new DropdownOverlay<>(pickerWidth, GROUP_BAR_HEIGHT);
        picker.setFontSize(13f);
        picker.setLabelProvider(ChatMenu::idOf);
        picker.setItems(chat.getParticipants());
        picker.setSelected(nextSpeaker());
        picker.onSelect(selected -> pickedSpeaker = selected);
        picker.setPosition(pickerX, 0);
        bar.addElement(picker);

        ButtonOverlay reply = Components.secondaryButton("Let them reply", 13f, replyWidth, GROUP_BAR_HEIGHT);
        reply.setPosition(pickerX + pickerWidth + 12, 0);
        reply.setTooltip("Have this character reply without sending a message (or send with an empty box)");
        reply.onAction(this::replyWithoutMessage);
        bar.addElement(reply);
        return bar;
    }

    // Saving a blank title clears it and the chat goes back to showing its date.
    private void renameChat() {
        Chat target = chat;
        Components.promptText("Rename Chat", dateLabel(target), target.getTitle(), "Save", 0, true, text -> {
            target.setTitle(text);
            owner.renderContent();
        }, () -> Scheduler.runLater(this::focusComposer));
    }

    // This screen always shows a chat, so deleting the last one starts a new one.
    private void confirmDeleteChat() {
        Chat target = chat;
        confirmDelete("Delete this chat?", "\"" + chatTitle(target) + "\" and all of its messages will be removed. This can't be undone.", () -> {
            if (target == generatingChat) generation.cancel();
            endEdit();
            try {
                character.deleteChat(target);
                App.logger.info("Deleted chat '{}' for '{}'", target.getName(), character.getId());
            } catch (IOException e) {
                App.logger.error("Could not delete chat '{}' for character '{}'", target.getName(), character.getId(), e);
                return;
            }
            List<Chat> remaining = character.getChats();
            chat = remaining.isEmpty() ? createChat() : remaining.getLast();
            pickedSpeaker = null;
            allowNames();
            messageList = null;
        });
    }

    // Named by when it was created so name order is creation order. Starts with the character's first message.
    // A new chat from a group chat has the same group.
    private Chat createChat() {
        Chat created = character.createChat(character, newChatName());
        if (chat != null && chat.isGroup()) created.setMembers(chat.getMembers());
        String firstMessage = character.getFirstMessage();
        if (!firstMessage.isBlank()) {
            created.addMessage(Role.ASSISTANT, character, firstMessage);
        }
        return created;
    }

    private String newChatName() {
        String base = "chat-" + LocalDateTime.now().format(CHAT_NAME_FORMAT);
        String name = base;
        int suffix = 2;
        // Two chats in the same second would share a file otherwise.
        while (chatNameTaken(name)) {
            name = base + "-" + suffix++;
        }
        return name;
    }

    // Starts a new chat with a copy of this message and everything before it, and opens it. The original is left as it was,
    // so the conversation can go two ways from here. The title says where it came from, they can rename it.
    private void branchFrom(int index) {
        if (generation != null) return;
        Chat source = chat;
        Chat branch = character.createChat(character, newChatName());
        branch.copyMessagesFrom(source, index);
        branch.setTitle(chatTitle(source) + " (branch)");
        App.logger.info("Branched chat '{}' at message {} into '{}' for '{}'", source.getName(), index + 1, branch.getName(), character.getId());
        switchTo(branch);
    }

    private boolean chatNameTaken(String name) {
        for (Chat existing : character.getChats()) {
            if (existing.getName().equals(name)) return true;
        }
        return Files.exists(character.getChatDirectory().resolve(name + ".dat"));
    }

    // "Title · 12 messages"
    private static String chatLabel(Chat chat) {
        int count = chat.getMessages().size();
        return chatTitle(chat) + " · " + count + (count == 1 ? " message" : " messages");
    }

    private static String chatTitle(Chat chat) {
        return chat.getTitle().isBlank() ? dateLabel(chat) : chat.getTitle();
    }

    // "Sep 23, 2026 · 2:05 PM" for our chat-yyyyMMdd-HHmmss names. Anything else shows the file name.
    private static String dateLabel(Chat chat) {
        String name = chat.getName();
        if (name.startsWith("chat-") && name.length() >= 20) {
            try {
                return LocalDateTime.parse(name.substring(5, 20), CHAT_NAME_FORMAT).format(CHAT_LABEL_FORMAT);
            } catch (DateTimeParseException ignored) {
                // Not one of ours.
            }
        }
        return name;
    }

    // Messages

    // Laying out the text of every message is most of the cost of opening a chat, and doing it all in one frame freezes the
    // window. So a chat is prepared a slice per frame behind a loading screen first. That fills the style's cache of laid out
    // text, which the real draw then reuses. A short chat is done within the first slice and never shows the loading screen.
    private boolean prepare(double rowWidth) {
        if (preparedChat == chat && preparedWidth == rowWidth) return true;
        int token = ++loadToken;
        loadMessages = new ArrayList<>(chat.getMessages());
        loadCursor = 0;
        style.startRedraw(); // The same pass the real draw makes, so the cache is asked for in the same order.
        if (prepareSlice(rowWidth, FIRST_SLICE_NANOS)) return true;
        Scheduler.runLater(() -> continuePreparing(token, rowWidth));
        return false;
    }

    // Always lays out at least one message so it keeps moving. True when every message has been done.
    private boolean prepareSlice(double rowWidth, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        while (loadCursor < loadMessages.size()) {
            buildRow(loadMessages, loadCursor, rowWidth);
            loadCursor++;
            if (System.nanoTime() >= deadline) break;
        }
        if (loadCursor < loadMessages.size()) return false;
        preparedChat = chat;
        preparedWidth = rowWidth;
        loadMessages = null;
        return true;
    }

    // A newer prepare (another chat, a resize) bumps the token and this one stops.
    private void continuePreparing(int token, double rowWidth) {
        if (token != loadToken || closed || loadMessages == null) return;
        if (prepareSlice(rowWidth, SLICE_NANOS)) {
            owner.renderContent();
            return;
        }
        if (loadBar != null) loadBar.setProgress((double) loadCursor / loadMessages.size());
        Scheduler.runLater(() -> continuePreparing(token, rowWidth));
    }

    private Element buildLoader(double width, double height) {
        VerticalLayout box = new VerticalLayout(width, height);
        box.setAlignment(Layout.Alignment.CENTER);
        box.setSpacing(12f);
        Components.clearStyle(box);

        box.addElement(Components.gradientBadge(56, Feather.MESSAGE_CIRCLE, 24));
        box.addElement(new TextOverlay("Opening chat with " + chatName() + "...", 15f, Palette.text()));
        loadBar = new ProgressBarOverlay(Math.min(260, width), 6);
        loadBar.setFillColor(Palette.accent());
        loadBar.setStripes(new Color(1f, 1f, 1f, 0.25f));
        loadBar.setProgress(loadMessages == null ? 0 : (double) loadCursor / loadMessages.size());
        box.addElement(loadBar);
        return box;
    }

    // The width a message gets, the list's width less its scroll bar.
    private static double rowWidthFor(double width) {
        ScrollContainer probe = new ScrollContainer(width, 0);
        double reserve = probe.getScrollBarStyle().getThickness() + 2 * probe.getScrollBarStyle().getMargin();
        return Math.max(0, width - reserve);
    }

    // Null when the pending reply takes the message's place.
    private Element buildRow(List<ChatMessage> messages, int index, double rowWidth) {
        ChatMessage message = messages.get(index);
        if (isReplacedByPending(message)) return null;
        Character author = authorOf(message);
        return style.buildMessage(shown(message), author, Speaker.of(author), userSpeaker, rowWidth, actionsFor(index, message, index == messages.size() - 1));
    }

    private Element buildMessageList(double width, double height) {
        // Stay where they were scrolled for an edit or delete further up, but follow the bottom for new messages.
        boolean followBottom = messageList == null
                || chat.getMessages().size() > renderedMessageCount
                || isShowingPendingReply()
                || messageList.getScrollOffsetY() >= messageList.getMaxScrollY() - 1;
        double previousOffset = messageList == null ? 0 : messageList.getScrollOffsetY();

        ScrollContainer scroll = new ScrollContainer(width, height);
        double reserve = scroll.getScrollBarStyle().getThickness() + 2 * scroll.getScrollBarStyle().getMargin();
        double rowWidth = Math.max(0, width - reserve);

        // An array list, this is indexed for every message.
        List<ChatMessage> messages = new ArrayList<>(chat.getMessages());
        style.startRedraw();
        double y = 0;
        for (int i = 0; i < messages.size(); i++) {
            Element row = buildRow(messages, i, rowWidth);
            if (row == null) continue; // The pending row takes its place.
            row.setPosition(0, y);
            scroll.addElement(row);
            y += row.getHeight() + style.messageGap();
        }

        pendingRow = null;
        if (isShowingPendingReply()) {
            pendingRowY = y;
            pendingRowWidth = rowWidth;
            pendingRow = buildPendingRow();
            pendingRow.setPosition(0, pendingRowY);
            scroll.addElement(pendingRow);
        }

        scroll.scrollTo(0, followBottom ? scroll.getMaxScrollY() : previousOffset);
        messageList = scroll;
        renderedMessageCount = messages.size();
        return scroll;
    }

    // Branching is on every message but not while generating, the reply that's coming in wouldn't be in the copy.
    // Regenerate, continue and the reply picker are only on the newest message and not while generating. Changing a
    // message further up would leave everything after it replying to text that isn't there anymore.
    // A lone assistant message is the greeting, there's nothing for it to be a reply to so it can't be regenerated.
    private MessageActions actionsFor(int index, ChatMessage message, boolean newest) {
        boolean idle = newest && generation == null;
        boolean canRegenerate = idle && (message.getSender() == Role.USER || index > 0);
        boolean canContinue = idle && message.getSender() == Role.ASSISTANT && !message.getContent().isEmpty();
        MessageActions.Variants variants = idle && message.getVariantCount() > 1
                ? new MessageActions.Variants(message.getActiveIndex(), message.getVariantCount(),
                () -> showVariant(message, message.getActiveIndex() - 1),
                () -> showVariant(message, message.getActiveIndex() + 1))
                : null;
        MessageActions.Editing editing = message == editingMessage ? new MessageActions.Editing(editBody, this::saveEdit, this::cancelEdit) : null;
        return new MessageActions(
                () -> Clipboard.copy(PromptBuilder.format(message.getContent(), authorOf(message), character)),
                caret -> startEdit(message, caret),
                canRegenerate ? this::regenerate : null,
                canContinue ? this::continueReply : null,
                idle && canUndo() ? this::undo : null,
                generation == null ? () -> branchFrom(index) : null,
                () -> confirmDeleteMessage(index, message),
                variants,
                editing,
                newest);
    }

    // The keyboard shortcuts, always on the newest message like the buttons that have them. Nothing changes a message while
    // a reply is generating, only Stop works then.
    private void shortcut(ChatShortcuts shortcut) {
        if (shortcut == ChatShortcuts.STOP) {
            stop();
            return;
        }
        if (shortcut == ChatShortcuts.NEW_CHAT) {
            switchTo(createChat());
            return;
        }
        if (generation != null || chat.getMessages().isEmpty()) return;
        int index = chat.getMessages().size() - 1;
        ChatMessage newest = chat.getMessages().get(index);
        switch (shortcut) {
            case REGENERATE -> regenerate();
            case CONTINUE -> continueReply();
            case EDIT -> startEdit(newest, -1);
            case DELETE -> confirmDeleteMessage(index, newest);
            case UNDO -> undo();
            case PREVIOUS -> showVariant(newest, newest.getActiveIndex() - 1);
            case NEXT -> showVariant(newest, newest.getActiveIndex() + 1);
            default -> {
            }
        }
    }

    // The newest message is a reply to a prompt. Nothing for the greeting, there's no prompt behind it.
    private boolean canUndo() {
        List<ChatMessage> messages = chat.getMessages();
        if (generation != null || messages.size() < 2) return false;
        return messages.getLast().getSender() == Role.ASSISTANT && messages.get(messages.size() - 2).getSender() == Role.USER;
    }

    // Takes back the newest reply (all of its responses) and the prompt before it, without asking, and puts that
    // prompt in the message box to change and send again. Whatever was already typed there is kept below it.
    private void undo() {
        if (!canUndo()) return;
        endEdit();
        List<ChatMessage> messages = chat.getMessages(); // A copy, the indexes are from before the removals.
        int index = messages.size() - 1;
        String prompt = messages.get(index - 1).getContent();
        chat.removeMessage(index);
        chat.removeMessage(index - 1);

        String draft = input.getText().trim();
        input.setText(draft.isEmpty() ? prompt : prompt + "\n\n" + draft);
        owner.renderContent();
    }

    private void showVariant(ChatMessage message, int variant) {
        if (variant < 0 || variant >= message.getVariantCount()) return;
        chat.setActiveVariant(message, variant);
        owner.renderContent();
    }

    // Asks for another reply. If the newest message is already a reply the new one is added to it as
    // another variant so they can flip between them. If it fails nothing is added.
    private void regenerate() {
        if (generation != null) return;
        List<ChatMessage> messages = chat.getMessages();
        if (messages.isEmpty()) return;
        int last = messages.size() - 1;
        ChatMessage newest = messages.get(last);
        if (newest.getSender() == Role.ASSISTANT) {
            if (last == 0) return;
            regeneratingMessage = newest;
        }
        startReply();
        owner.renderContent();
    }

    // Asks the model to carry on from the end of the newest reply. The text that comes in is added to it, so
    // there's no variant. If the model has nothing to add the message is left as it was and a warning shows.
    private void continueReply() {
        if (generation != null) return;
        List<ChatMessage> messages = chat.getMessages();
        if (messages.isEmpty()) return;
        ChatMessage newest = messages.getLast();
        if (newest.getSender() != Role.ASSISTANT || newest.getContent().isEmpty()) return;
        endEdit();
        continuingMessage = newest;
        continuingText = newest.getContent();
        startReply();
        owner.renderContent();
    }

    // The full text of the continued message from what the server sent. llama.cpp sends the whole message
    // back with the new text on the end, so the part that's already there is skipped. Any other server
    // only sends the new text, which is glued on.
    private String continued(Character speaker, String generated) {
        if (generated.isEmpty()) return continuingText;
        String prefix = PromptBuilder.format(continuingText, speaker, character);
        if (generated.startsWith(prefix)) return continuingText + generated.substring(prefix.length());
        char end = continuingText.charAt(continuingText.length() - 1), start = generated.charAt(0);
        boolean glued = java.lang.Character.isWhitespace(end) || java.lang.Character.isWhitespace(start) || ".,!?;:)]}'\"…".indexOf(start) >= 0;
        return continuingText + (glued ? "" : " ") + generated;
    }

    // Turns the message into an editor with the caret where they double clicked (-1 for the end).
    // Only one message at a time. Enter adds a line, Cmd/Ctrl+Enter saves and Escape cancels.
    private void startEdit(ChatMessage message, int caret) {
        if (message == editingMessage) return;
        if (isReplacedByPending(message)) return; // Its text isn't on screen.
        endEdit();

        MessageBody body = new MessageBody(style.look(), message.getContent(), true);
        if (caret >= 0) body.placeCaret(Math.min(caret, message.getContent().length()));
        body.onCancel(this::cancelEdit);
        body.onSubmit(text -> saveEdit());
        // The editor grows with its text, so redraw when the height changes (not on every key).
        body.onTextChanged(text -> {
            double before = body.getHeight();
            body.fit(body.getWidth());
            if (body.getHeight() != before) {
                Scheduler.runLater(() -> {
                    if (editBody == body && !closed) owner.renderContent();
                });
            }
        });

        editingMessage = message;
        editBody = body;
        owner.renderContent();
        Scheduler.runLater(() -> {
            if (editBody == body) App.window.requestFocus(body);
        });
    }

    private void saveEdit() {
        if (editingMessage == null) return;
        String text = editBody.getText().trim();
        if (text.isEmpty()) return; // Can't empty a message out, delete it instead.
        int index = chat.getMessages().indexOf(editingMessage);
        if (index >= 0) chat.setMessageContent(index, text);
        endEdit();
        owner.renderContent();
    }

    private void cancelEdit() {
        if (editingMessage == null) return;
        endEdit();
        owner.renderContent();
    }

    // Doesn't redraw, the caller does that.
    private void endEdit() {
        if (editBody != null && App.window.getFocusedElement() == editBody) App.window.requestFocus(null);
        editingMessage = null;
        editBody = null;
    }

    // A message with several replies deletes only the one being shown, unless "all" is ticked.
    // With the warning off it goes straight to that default.
    private void confirmDeleteMessage(int index, ChatMessage message) {
        boolean several = message.getVariantCount() > 1;
        Runnable deleteShown = () -> {
            if (several) chat.removeActiveVariant(message);
            else chat.removeMessage(index);
        };
        if (!confirmMessageDeletes) {
            deleteShown.run();
            owner.renderContent();
            return;
        }

        List<String> options = new ArrayList<>();
        if (several) options.add("Delete all " + message.getVariantCount() + " responses and the message");
        options.add("Don't ask again this session");
        String title = several ? "Delete this response?" : "Delete this message?";
        String body = several
                ? "Only the response you're viewing is removed; the message's other responses are kept. This can't be undone."
                : "This can't be undone.";
        Components.confirmDelete(title, body, options, ticked -> {
            boolean all = several && ticked[0];
            if (ticked[ticked.length - 1]) confirmMessageDeletes = false;
            if (all) chat.removeMessage(index);
            else deleteShown.run();
            owner.renderContent();
        }, () -> Scheduler.runLater(this::focusComposer));
    }

    private void confirmDelete(String title, String body, Runnable onDelete) {
        Components.confirmDelete(title, body, () -> {
            onDelete.run();
            owner.renderContent();
        }, () -> Scheduler.runLater(this::focusComposer));
    }

    // Sending

    private void send() {
        if (generation != null) return; // One reply at a time.
        String text = input.getText().trim();
        if (text.isEmpty()) {
            // In a group chat an empty box lets the next character speak, so they can talk among themselves.
            if (chat.isGroup()) replyWithoutMessage();
            return;
        }
        chat.addMessage(Role.USER, character, text);
        input.setText("");
        startReply();
        owner.renderContent();
    }

    // A reply from the next character with no new message from the user. Whatever is typed in the box stays there.
    private void replyWithoutMessage() {
        if (generation != null || chat.getMessages().isEmpty()) return;
        endEdit();
        startReply();
        owner.renderContent();
    }

    // Regenerating or continuing a message is always the character who wrote it, otherwise it's the next one up.
    private Character replySpeaker() {
        if (regeneratingMessage != null) return authorOf(regeneratingMessage);
        if (continuingMessage != null) return authorOf(continuingMessage);
        return nextSpeaker();
    }

    // Keeps whatever text already came in.
    private void stop() {
        if (generation != null) generation.cancel();
    }

    // The listener runs on the request thread so everything it does goes through Scheduler.runLater.
    private void startReply() {
        ModelSettings settings = App.instance.getModelSettings();
        Character speaker = replySpeaker();
        // Everyone in the chat but the one replying. Nobody else means the same single character chat as always.
        List<Character> others = new ArrayList<>(chat.getParticipants());
        others.removeIf(other -> other.getId().equals(speaker.getId()));
        boolean group = chat.isGroup() || !speaker.getId().equals(character.getId());

        List<ChatMessage> history = chat.getMessages();
        history.removeIf(m -> m == regeneratingMessage);
        JSONArray prompt = PromptBuilder.build(speaker, character, group ? others : List.of(), history, settings.contextSize.get(), settings.responseTokens.get());
        // The local server continues a trailing assistant message by itself, a cloud endpoint has to be told to.
        if (continuingMessage != null && settings.usesCloud()) PromptBuilder.withContinueNote(prompt);
        // The same character speaking twice in a row would otherwise carry on its last reply instead of starting a new one.
        else if (group && continuingMessage == null) PromptBuilder.withTurnNote(prompt, speaker, character);

        generatingChat = chat;
        generatingSpeaker = speaker;
        pendingReply = new ChatMessage(Role.ASSISTANT, speaker, continuingText);
        generationError = null;
        latestContent = "";
        latestReasoning = "";

        List<String> otherNames = group ? PromptBuilder.otherNames(speaker, chat.getParticipants()) : List.of();
        generation = ChatCompletion.start(prompt, PromptBuilder.userName(character), otherNames, settings.responseTokens.get(), grammar(), new ChatCompletion.Listener() {
            @Override
            public void onProgress(String content, String reasoning) {
                latestContent = content;
                latestReasoning = reasoning;
                if (!progressQueued.getAndSet(true)) {
                    Scheduler.runLater(ChatMenu.this::applyProgress);
                }
            }

            @Override
            public void onComplete(String content, String reasoning, boolean cancelled) {
                Scheduler.runLater(() -> finishReply(content, reasoning, null, cancelled));
            }

            @Override
            public void onError(String message) {
                Scheduler.runLater(() -> finishReply("", "", message, false));
            }
        });
    }

    // Holds the local model to the reply format when the character asks for it strictly.
    private String grammar() {
        DialogueFormat format = DialogueFormat.of(generatingSpeaker);
        if (format == null || !generatingSpeaker.isStrictFormat()) return null;
        return continuingMessage != null ? format.continueGrammar(continuingText) : format.grammar();
    }

    // A reply with a format always starts with its prefix (the name), even if the model left it off.
    // Without one, a group chat's reply loses a "Name:" label the model copied from the others' messages.
    private String named(Character speaker, Chat in, String content) {
        DialogueFormat format = DialogueFormat.of(speaker);
        if (format != null) return format.withPrefix(content);
        return in.isGroup() ? PromptBuilder.withoutLabel(content, speaker.getDisplayName().isBlank() ? "Character" : speaker.getDisplayName()) : content;
    }

    private void applyProgress() {
        progressQueued.set(false);
        if (pendingReply == null) return;
        Character speaker = generatingSpeaker;
        pendingReply.setContent(continuingMessage != null ? continued(speaker, latestContent) : named(speaker, generatingChat, latestContent));
        pendingReply.setReasoning(latestReasoning);
        if (!closed) refreshPendingRow();
    }

    // Swaps just the pending row and keeps the list at the bottom if it was already there.
    // Falls back to a full redraw if the row isn't on screen yet.
    private void refreshPendingRow() {
        if (!isShowingPendingReply()) return;
        if (pendingRow == null || messageList == null) {
            owner.renderContent();
            return;
        }
        boolean atBottom = messageList.getScrollOffsetY() >= messageList.getMaxScrollY() - 1;
        messageList.removeElement(pendingRow);
        pendingRow = buildPendingRow();
        pendingRow.setPosition(0, pendingRowY);
        messageList.addElement(pendingRow);
        if (atBottom) messageList.scrollTo(0, messageList.getMaxScrollY());
    }

    // Placeholders are only filled in on screen and in the prompt. The chat file keeps what was written, so editing
    // shows the placeholder again and a changed value reaches the old messages too.
    private ChatMessage shown(ChatMessage message) {
        Character author = authorOf(message);
        return new ChatMessage(message.getSender(), author, PromptBuilder.format(message.getContent(), author, character));
    }

    // No actions on it, there's nothing to copy or edit until it's done.
    private Element buildPendingRow() {
        String content = pendingReply.getContent();
        if (content.isEmpty()) {
            if (!App.instance.getModelSettings().usesCloud() && ServerProcess.get().getState() == ServerProcess.State.STARTING) content = "Loading the model...";
            else if (pendingReply.hasReasoning()) content = "Thinking...";
            else content = "...";
        }
        Character speaker = generatingSpeaker;
        ChatMessage placeholder = new ChatMessage(Role.ASSISTANT, speaker, PromptBuilder.format(content, speaker, character));
        return style.buildMessage(placeholder, speaker, Speaker.of(speaker), userSpeaker, pendingRowWidth, MessageActions.NONE);
    }

    private boolean isReplacedByPending(ChatMessage message) {
        return (message == regeneratingMessage || message == continuingMessage) && isShowingPendingReply();
    }

    private boolean isShowingPendingReply() {
        return pendingReply != null && chat == generatingChat;
    }

    // Saves the reply to the chat it was made for, as a new message or another variant of the one being
    // regenerated. Nothing is saved if that chat was deleted or nothing came in.
    private void finishReply(String content, String reasoning, String error, boolean cancelled) {
        Chat target = generatingChat;
        Character speaker = generatingSpeaker;
        ChatMessage regenerated = regeneratingMessage;
        ChatMessage extended = continuingMessage;
        String original = continuingText;
        String merged = extended == null ? named(speaker, target, content) : continued(speaker, content);
        generation = null;
        generatingChat = null;
        generatingSpeaker = null;
        pendingReply = null;
        pendingRow = null;
        regeneratingMessage = null;
        continuingMessage = null;
        continuingText = "";

        if (error != null) {
            generationError = extended != null ? "Couldn't continue the message: " + error : error;
        } else if (extended != null) {
            if (!character.getChats().contains(target)) {
                // Deleted while it generated, nothing to save to.
            } else if (merged.equals(original)) {
                if (!cancelled) generationError = "The model had nothing to add, the message looks finished.";
            } else {
                target.setMessageContent(target.getMessages().indexOf(extended), merged);
            }
        } else if (!content.isEmpty() && character.getChats().contains(target)) {
            String kept = reasoning.isEmpty() ? null : reasoning;
            if (regenerated != null) {
                target.addVariant(regenerated, merged, kept);
            } else {
                target.addMessage(Role.ASSISTANT, speaker, merged, null, kept);
                // The next reply goes to whoever is next in turn again, unless they pick someone else.
                pickedSpeaker = null;
            }
        }
        if (!closed) owner.renderContent();
    }

    // The text area also runs the chat's shortcuts, see ChatShortcuts. It sends on Enter and adds a new line on Shift+Enter.
    // The base class does the opposite (Enter is a new line, Ctrl/Cmd+Enter submits), so Enter is caught here first. Shift+Enter is passed
    // on without the modifier so the base class handles it like a normal new line.
    private static final class ComposerArea extends RichTextAreaOverlay {
        private final Runnable onSend;
        private final Consumer<ChatShortcuts> onShortcut;

        ComposerArea(double height, Runnable onSend, Consumer<ChatShortcuts> onShortcut) {
            super(10, height);
            this.onSend = onSend;
            this.onShortcut = onShortcut;
        }

        @Override
        public void onKeyPressed(int key, int action, int mods) {
            ChatShortcuts shortcut = ChatShortcuts.of(key, mods);
            if (shortcut != null) {
                // Not on repeat, holding the keys shouldn't regenerate over and over.
                if (action == GLFW.GLFW_PRESS) onShortcut.accept(shortcut);
                return;
            }
            boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
            if (!enter || action == GLFW.GLFW_RELEASE) {
                super.onKeyPressed(key, action, mods);
                return;
            }
            if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) {
                super.onKeyPressed(key, action, 0);
            } else if (action == GLFW.GLFW_PRESS) {
                // Not on repeat, holding Enter shouldn't send over and over.
                onSend.run();
            }
        }
    }
}
