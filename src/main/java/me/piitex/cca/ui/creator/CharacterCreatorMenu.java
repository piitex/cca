package me.piitex.cca.ui.creator;

import com.drew.imaging.ImageProcessingException;
import me.piitex.cca.App;
import me.piitex.cca.backend.server.DialogueFormat;
import me.piitex.cca.card.CardImporter;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.User;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.GrammarChecker;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

// Creates a new character, or edits one when a character is passed in.
// Steps: details, lorebook, user, user lorebook, chat setup, finish. The character keeps its own copy of the user,
// a template from the Users page only fills the fields in.
public class CharacterCreatorMenu extends CreatorMenu {
    private static final String[] STEPS = {"Character", "Lorebook", "User", "User Lorebook", "Chat Setup", "Finish"};
    private static final int DETAILS = 0, LORE = 1, USER = 2, USER_LORE = 3, CHAT = 4;

    private TextFieldOverlay nameField;
    private TextFieldOverlay idField;
    private TextAreaOverlay personaField;
    private TextAreaOverlay firstMessageField;
    private TextAreaOverlay scenarioField;
    private TextFieldOverlay replyFormatField;
    private boolean strictFormat;
    private final LorebookStep lore;
    private final PlaceholderList placeholders;

    // The person the character chats with, these are the character's own fields.
    private TextFieldOverlay userNameField;
    private TextAreaOverlay userPersonaField;
    private final LorebookStep userLore;
    private final PlaceholderList userPlaceholders;
    private Path userIconFile;

    // The picture behind this character's chats. Like the icon, it's only copied into the character's folder when they save.
    private Path backgroundFile;

    // Templates to fill the user from, read once when the creator opens.
    private final List<User> users = new ArrayList<>(User.loadAll(App.environment));

    // The id follows the name until they type in the id field themselves.
    private boolean idEdited = false;

    // Null for a new character. When editing, the id is locked since the chats live in a folder named after it.
    private final Character editing;

    public CharacterCreatorMenu(MainMenu owner) {
        this(owner, null);
    }

    // Set when duplicating: the fields start as a copy of this one, but it saves as a new character.
    private final Character duplicateOf;

    public CharacterCreatorMenu(MainMenu owner, Character editing) {
        this(owner, editing, null);
    }

    public static CharacterCreatorMenu duplicating(MainMenu owner, Character original) {
        return new CharacterCreatorMenu(owner, null, original);
    }

    private CharacterCreatorMenu(MainMenu owner, Character editing, Character duplicateOf) {
        super(owner);
        this.editing = editing;
        this.duplicateOf = duplicateOf;
        this.lore = new LorebookStep(this, owner);
        this.userLore = new LorebookStep(this, owner);
        this.placeholders = new PlaceholderList(owner);
        this.userPlaceholders = new PlaceholderList(owner);
        users.sort(Comparator.comparing(u -> u.getDisplayName().toLowerCase()));
        initFields();
        Character source = editing != null ? editing : duplicateOf;
        if (source != null) {
            GrammarChecker checker = GrammarChecker.shared();
            if (checker != null) checker.allow(source.getDisplayName(), source.getUserName());
            nameField.setText(source.getDisplayName());
            if (editing != null) {
                idField.setText(editing.getId());
                idField.setReadOnly(true);
            } else {
                idField.setText(uniqueId(source.getId() + "-copy", candidate -> Character.exists(App.environment, candidate)));
            }
            idEdited = true;
            personaField.setText(source.getPersona());
            firstMessageField.setText(source.getFirstMessage());
            scenarioField.setText(source.getChatScenario());
            replyFormatField.setText(source.getReplyFormat());
            strictFormat = source.isStrictFormat();
            lore.entries().putAll(source.getLorebook());
            placeholders.entries().putAll(source.getPlaceholders());
            userPlaceholders.entries().putAll(source.getUserPlaceholders());
            userNameField.setText(source.getUserName());
            userPersonaField.setText(source.getUserPersona());
            userLore.entries().putAll(source.getUserLorebook());
            Path userIcon = Path.of(source.getUserIconPath());
            if (Files.isRegularFile(userIcon)) {
                userIconFile = userIcon;
            }
            Path icon = Path.of(source.getIconPath());
            if (Files.isRegularFile(icon)) {
                pendingIconFile = icon;
            }
            Path background = source.getBackgroundFile();
            if (background != null && Files.isRegularFile(background)) {
                backgroundFile = background;
            }
        }
    }

    public boolean isEditing() {
        return editing != null;
    }

    @Override
    public String getTitle() {
        return isEditing() ? "Edit Character" : "New Character";
    }

    @Override
    protected String[] stepLabels() {
        return STEPS;
    }

    // Lore and chat setup are optional, the character and its user each need a name.
    @Override
    protected boolean isStepValid(int step) {
        return switch (step) {
            case DETAILS -> !nameField.getText().trim().isEmpty();
            case USER -> !userNameField.getText().trim().isEmpty();
            default -> true;
        };
    }

    @Override
    protected Element buildStep(int step, double width, double height) {
        return switch (step) {
            case DETAILS -> buildDetailsStep(width, height);
            case LORE -> lore.build(width, height);
            case USER -> buildUserStep(width, height);
            case USER_LORE -> userLore.build(width, height);
            case CHAT -> buildChatStep(width, height);
            default -> buildFinishStep(width, height);
        };
    }

    private void initFields() {
        idField = new TextFieldOverlay(10, 36);
        idField.setPlaceholder("auto-generated from name");
        idField.onTextChanged(text -> {
            idEdited = true;
            fieldEdited();
        });

        nameField = new TextFieldOverlay(10, 36);
        nameField.setPlaceholder("e.g. Aria");
        nameField.onTextChanged(text -> {
            // setText doesn't fire idField's listener, so this can't flip idEdited on its own.
            if (!idEdited) {
                idField.setText(slugify(text));
            }
            fieldEdited();
        });

        personaField = Components.textArea(200);
        personaField.setPlaceholder("Personality, appearance, speech style...");

        userNameField = new TextFieldOverlay(10, 36);
        userNameField.setPlaceholder("e.g. Alex");
        userNameField.onTextChanged(text -> fieldEdited());

        userPersonaField = Components.textArea(200);
        userPersonaField.setPlaceholder("Personality, appearance, how you come across...");

        firstMessageField = Components.textArea(100);
        firstMessageField.setPlaceholder("What they say to open the chat...");

        scenarioField = Components.textArea(100);
        scenarioField.setPlaceholder("The setting or situation for the chat...");

        replyFormatField = new TextFieldOverlay(10, 36);
        replyFormatField.setPlaceholder("e.g. {{char}}: \"What they say.\" *What they do.*");
    }

    private Element buildDetailsStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);

        double y = 0;
        // Importing replaces everything in the form, which is wrong when editing.
        if (!isEditing()) {
            y = placeImportButton(scroll, y, fieldWidth, this::pickCharacterCard);
        }
        y = placeField(scroll, "Name", nameField, y, fieldWidth, 36);
        y = placeField(scroll, "Character ID", idField, y, fieldWidth, 36);
        y = placeIconField(scroll, y, fieldWidth);
        y = placeBackgroundField(scroll, "Chat background (optional, used instead of the one from Settings)", y, fieldWidth,
                backgroundFile, path -> backgroundFile = path);
        y = placeField(scroll, "Persona", personaField, y, fieldWidth, 200);
        placeholders.place(scroll, "the character's", y, fieldWidth);
        return scroll;
    }

    // Fills in the whole form from a SillyTavern or BackyardAI card. The card image becomes the icon.
    private void pickCharacterCard() {
        FileDialog.open()
                .filter("Character Card", "png")
                .onSelect(path -> {
                    try {
                        applyCardData(CardImporter.readCharacterCard(path.toFile()));
                        pendingIconFile = path;
                        error = null;
                        App.logger.info("Imported character card '{}'", path.getFileName());
                    } catch (ImageProcessingException | IOException e) {
                        App.logger.error("Could not import character card '{}'", path, e);
                        error = "Couldn't read that file as a character card.";
                    }
                    owner.renderContent();
                })
                .show();
    }

    private void applyCardData(CardImporter.CardData data) {
        if (!data.displayName().isEmpty()) {
            nameField.setText(data.displayName());
            // setText doesn't fire the name listener, so the id has to be filled here.
            if (!idEdited) idField.setText(slugify(data.displayName()));
        }
        if (!data.id().isEmpty()) {
            idField.setText(data.id());
            idEdited = true;
        }
        if (!data.persona().isEmpty()) {
            personaField.setText(data.persona());
        }
        if (!data.firstMessage().isEmpty()) {
            firstMessageField.setText(data.firstMessage());
        }
        if (!data.scenario().isEmpty()) {
            scenarioField.setText(data.scenario());
        }
        if (!data.lorebook().isEmpty()) {
            lore.entries().clear();
            lore.entries().putAll(data.lorebook());
        }
        if (!data.placeholders().isEmpty()) {
            placeholders.entries().clear();
            placeholders.entries().putAll(data.placeholders());
        }
        if (!data.replyFormat().isEmpty()) {
            replyFormatField.setText(data.replyFormat());
            strictFormat = data.strictFormat();
        }
        if (data.background() != null) {
            try {
                // The editor works with files, the background is only copied into the character when it's saved.
                Path file = Files.createTempFile("cca-background", ".img");
                file.toFile().deleteOnExit();
                Files.write(file, data.background());
                backgroundFile = file;
            } catch (IOException e) {
                App.logger.error("Could not read the chat background of the card", e);
            }
        }
    }

    private Element buildChatStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);

        double y = placeField(scroll, "First message", firstMessageField, 0, fieldWidth, 100);
        y = placeField(scroll, "Chat scenario", scenarioField, y, fieldWidth, 100);
        y = placeReplyFormat(scroll, y, fieldWidth);
        placeToggle(scroll, "Strict", "Makes a local model follow the reply format every time. Thinking is skipped while it's on.", strictFormat,
                on -> strictFormat = on, y, fieldWidth);
        return scroll;
    }

    // The example reply with a preset picker next to it that fills it in.
    private double placeReplyFormat(ScrollContainer scroll, double y, double width) {
        double pickerWidth = 230;
        double next = placeField(scroll, "Reply format", replyFormatField, y, width - pickerWidth - 10, 36);

        DropdownOverlay<DialogueFormat.Preset> presets = new DropdownOverlay<>(pickerWidth, 36);
        presets.setFontSize(14f);
        presets.setPlaceholder("Presets...");
        presets.setLabelProvider(DialogueFormat.Preset::name);
        presets.setItems(DialogueFormat.PRESETS);
        presets.onSelect(preset -> {
            presets.close();
            replyFormatField.setText(preset.example());
        });
        presets.setPosition(width - pickerWidth, replyFormatField.getY());
        scroll.addElement(presets);

        TextFlowOverlay hint = new TextFlowOverlay("An example reply the character's replies follow. {{char}} is their name and "
                + "the marks around each part carry over. Leave it empty to let the model write freely.", width, 13f, Palette.muted());
        hint.setTextColor(Palette.muted());
        hint.setPosition(0, next - 10);
        scroll.addElement(hint);
        return hint.getY() + hint.getHeight() + 18;
    }

    // A title and a hint on the left with the switch on the right. Returns the y for the next one.
    private double placeToggle(ScrollContainer scroll, String label, String hint, boolean on, Consumer<Boolean> onToggle, double y, double width) {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(44, 24, on);
        toggle.setOnColor(Palette.accent());
        toggle.onToggle(onToggle);
        toggle.setPosition(width - toggle.getWidth(), y + 4);
        scroll.addElement(toggle);

        TextOverlay title = new TextOverlay(label, 15f, Palette.text());
        title.setPosition(0, y);
        scroll.addElement(title);
        TextFlowOverlay hintText = new TextFlowOverlay(hint, width - toggle.getWidth() - 20, 13f, Palette.muted());
        hintText.setTextColor(Palette.muted());
        hintText.setPosition(0, y + title.getHeight() + 4);
        scroll.addElement(hintText);
        return hintText.getY() + hintText.getHeight() + 18;
    }

    // Who the character chats with. These are the character's own fields, a template only fills them in, so the character
    // keeps its user if the template changes or is deleted, and shares it with the character. Their name and icon
    // show in the chat and their personality goes into the prompt.
    private Element buildUserStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);

        double y = 0;
        if (!users.isEmpty()) {
            DropdownOverlay<String> picker = new DropdownOverlay<>(fieldWidth, 36);
            picker.setFontSize(14f);
            picker.setPlaceholder("Choose a template...");
            picker.setLabelProvider(this::templateName);
            picker.setItems(users.stream().map(User::getId).toList());
            picker.onSelect(id -> {
                // The redraw below removes the dropdown, after that it can no longer close its own list.
                picker.close();
                fillFromTemplate(id);
                owner.renderContent();
            });
            y = placeField(scroll, "Fill from a template (replaces the fields below)", picker, y, fieldWidth, 36);
        }

        y = placeField(scroll, "Name", userNameField, y, fieldWidth, 36);
        y = placeIconField(scroll, y, fieldWidth, userIconFile, path -> userIconFile = path);
        y = placeField(scroll, "Personality", userPersonaField, y, fieldWidth, 200);
        userPlaceholders.place(scroll, "the user's", y, fieldWidth);
        return scroll;
    }

    private void fillFromTemplate(String id) {
        for (User template : users) {
            if (!template.getId().equals(id)) continue;
            userNameField.setText(template.getDisplayName());
            userPersonaField.setText(template.getPersona());
            userLore.entries().clear();
            userLore.entries().putAll(template.getLorebook());
            userPlaceholders.entries().clear();
            userPlaceholders.entries().putAll(template.getPlaceholders());
            Path icon = Path.of(template.getIconPath());
            userIconFile = Files.isRegularFile(icon) ? icon : null;
            error = null;
            return;
        }
    }

    private String templateName(String id) {
        for (User user : users) {
            if (user.getId().equals(id)) return user.getDisplayName().isBlank() ? id : user.getDisplayName();
        }
        return id;
    }

    // Scrolls too, a long imported persona would push the button off the bottom otherwise.
    private Element buildFinishStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);
        double y = 0;

        String name = nameField.getText().trim();
        TextOverlay nameText = new TextOverlay("Name: " + (name.isEmpty() ? "(not set)" : name), 15f, Palette.text());
        nameText.setPosition(0, y);
        scroll.addElement(nameText);
        y += nameText.getHeight() + 8;

        String summary = (pendingIconFile != null ? "Icon set" : "No icon") + " · "
                + lore.entries().size() + (lore.entries().size() == 1 ? " lorebook entry" : " lorebook entries");
        String userName = userNameField.getText().trim();
        summary += " · chats as " + (userName.isEmpty() ? "(user not set)" : userName);
        TextOverlay summaryText = new TextOverlay(summary, 12f, Palette.muted());
        summaryText.setPosition(0, y);
        scroll.addElement(summaryText);
        y += summaryText.getHeight() + 14;

        String persona = personaField.getText().trim();
        TextFlowOverlay personaPreview = new TextFlowOverlay(persona.isEmpty() ? "No persona set." : persona, fieldWidth, 13f, Palette.muted());
        personaPreview.setPosition(0, y);
        scroll.addElement(personaPreview);
        y += personaPreview.getHeight() + 20;

        ButtonOverlay create = Components.accentButton(isEditing() ? "Save Changes" : "Create Character", 15f, 200, 44);
        create.setPosition(0, y);
        create.onAction(this::submit);
        scroll.addElement(create);
        y += 44 + 14;

        if (error != null) placeError(scroll, y);
        return scroll;
    }

    // The character and its user each need a name, and a new character an id nobody has.
    @Override
    protected Problem findProblem() {
        if (nameField.getText().trim().isEmpty()) {
            return new Problem(DETAILS, nameField, "Character name is required.");
        }
        if (editing == null) {
            String typedId = slugify(idField.getText().trim());
            if (!typedId.isEmpty() && Character.exists(App.environment, typedId)) {
                return new Problem(DETAILS, idField, "That character ID is already taken.");
            }
        }
        if (userNameField.getText().trim().isEmpty()) {
            return new Problem(USER, userNameField, "User name is required.");
        }
        return null;
    }

    private void submit() {
        if (!checkValid()) return;
        String name = nameField.getText().trim();
        String userName = userNameField.getText().trim();

        Character character;
        String id;
        if (editing != null) {
            character = editing;
            id = editing.getId();
        } else {
            String typedId = slugify(idField.getText().trim());
            id = typedId.isEmpty() ? uniqueId(name, candidate -> Character.exists(App.environment, candidate)) : typedId;
            character = new Character(App.environment, id);
        }

        // Typed but never added would be lost otherwise.
        placeholders.commit();
        userPlaceholders.commit();

        character.setDisplayName(name);
        character.setPersona(personaField.getText().trim());
        character.setFirstMessage(firstMessageField.getText().trim());
        character.setChatScenario(scenarioField.getText().trim());
        character.setReplyFormat(replyFormatField.getText().trim());
        character.setStrictFormat(strictFormat);
        character.setUserName(userName);
        character.setUserPersona(userPersonaField.getText().trim());
        character.setUserLorebook(userLore.entries());
        character.setUserPlaceholders(userPlaceholders.entries());
        character.setLorebook(lore.entries());
        character.setPlaceholders(placeholders.entries());
        if (pendingIconFile != null) {
            try {
                character.setIconFile(pendingIconFile);
            } catch (IOException e) {
                App.logger.error("Could not copy icon for character '{}'", id, e);
            }
        } else if (editing != null) {
            try {
                character.clearIcon();
            } catch (IOException e) {
                App.logger.error("Could not remove the icon of character '{}'", id, e);
            }
        }
        if (backgroundFile != null) {
            try {
                character.setBackgroundFile(backgroundFile);
            } catch (IOException e) {
                App.logger.error("Could not copy the chat background for character '{}'", id, e);
            }
        } else if (editing != null) {
            try {
                character.clearBackground();
            } catch (IOException e) {
                App.logger.error("Could not remove the chat background of character '{}'", id, e);
            }
        }
        if (userIconFile != null) {
            try {
                character.setUserIconFile(userIconFile);
            } catch (IOException e) {
                App.logger.error("Could not copy the user icon for character '{}'", id, e);
            }
        } else {
            try {
                character.clearUserIcon();
            } catch (IOException e) {
                App.logger.error("Could not remove the user icon of character '{}'", id, e);
            }
        }

        if (editing != null) {
            owner.onCharacterEdited(character);
        } else {
            owner.onCharacterCreated(character, duplicateOf);
        }
    }
}
