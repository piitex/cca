package me.piitex.cca.ui.creator;

import com.drew.imaging.ImageProcessingException;
import me.piitex.cca.App;
import me.piitex.cca.card.CardImporter;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.User;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final LorebookStep lore;

    // The person the character chats with, these are the character's own fields.
    private TextFieldOverlay userNameField;
    private TextAreaOverlay userPersonaField;
    private final LorebookStep userLore;
    private Path userIconFile;

    // Templates to fill the user from, read once when the creator opens.
    private final List<User> users = new ArrayList<>(User.loadAll(App.environment));

    // The id follows the name until they type in the id field themselves.
    private boolean idEdited = false;

    // Null for a new character. When editing, the id is locked since the chats live in a folder named after it.
    private final Character editing;

    public CharacterCreatorMenu(MainMenu owner) {
        this(owner, null);
    }

    public CharacterCreatorMenu(MainMenu owner, Character editing) {
        super(owner);
        this.editing = editing;
        this.lore = new LorebookStep(this, owner);
        this.userLore = new LorebookStep(this, owner);
        users.sort(Comparator.comparing(u -> u.getDisplayName().toLowerCase()));
        initFields();
        if (editing != null) {
            nameField.setText(editing.getDisplayName());
            idField.setText(editing.getId());
            idField.setReadOnly(true);
            idEdited = true;
            personaField.setText(editing.getPersona());
            firstMessageField.setText(editing.getFirstMessage());
            scenarioField.setText(editing.getChatScenario());
            lore.entries().putAll(editing.getLorebook());
            userNameField.setText(editing.getUserName());
            userPersonaField.setText(editing.getUserPersona());
            userLore.entries().putAll(editing.getUserLorebook());
            Path userIcon = Path.of(editing.getUserIconPath());
            if (Files.isRegularFile(userIcon)) {
                userIconFile = userIcon;
            }
            Path icon = Path.of(editing.getIconPath());
            if (Files.isRegularFile(icon)) {
                pendingIconFile = icon;
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
            error = null;
            highlightSteps();
        });

        nameField = new TextFieldOverlay(10, 36);
        nameField.setPlaceholder("e.g. Aria");
        nameField.onTextChanged(text -> {
            error = null;
            // setText doesn't fire idField's listener, so this can't flip idEdited on its own.
            if (!idEdited) {
                idField.setText(slugify(text));
            }
            highlightSteps();
        });

        personaField = new TextAreaOverlay(10, 200);
        personaField.setPlaceholder("Personality, appearance, speech style...");

        userNameField = new TextFieldOverlay(10, 36);
        userNameField.setPlaceholder("e.g. Alex");
        userNameField.onTextChanged(text -> {
            error = null;
            highlightSteps();
        });

        userPersonaField = new TextAreaOverlay(10, 200);
        userPersonaField.setPlaceholder("Personality, appearance, how you come across...");

        firstMessageField = new TextAreaOverlay(10, 100);
        firstMessageField.setPlaceholder("What they say to open the chat...");

        scenarioField = new TextAreaOverlay(10, 100);
        scenarioField.setPlaceholder("The setting or situation for the chat...");
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
        placeField(scroll, "Persona", personaField, y, fieldWidth, 200);
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
    }

    private Element buildChatStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);

        double y = placeField(scroll, "First message", firstMessageField, 0, fieldWidth, 100);
        placeField(scroll, "Chat scenario", scenarioField, y, fieldWidth, 100);
        return scroll;
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
        placeField(scroll, "Personality", userPersonaField, y, fieldWidth, 200);
        return scroll;
    }

    private void fillFromTemplate(String id) {
        for (User template : users) {
            if (!template.getId().equals(id)) continue;
            userNameField.setText(template.getDisplayName());
            userPersonaField.setText(template.getPersona());
            userLore.entries().clear();
            userLore.entries().putAll(template.getLorebook());
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
        TextOverlay nameText = new TextOverlay("Name: " + (name.isEmpty() ? "(not set)" : name), 15f, theme.getText());
        nameText.setPosition(0, y);
        scroll.addElement(nameText);
        y += nameText.getHeight() + 8;

        String summary = (pendingIconFile != null ? "Icon set" : "No icon") + " · "
                + lore.entries().size() + (lore.entries().size() == 1 ? " lorebook entry" : " lorebook entries");
        String userName = userNameField.getText().trim();
        summary += " · chats as " + (userName.isEmpty() ? "(user not set)" : userName);
        TextOverlay summaryText = new TextOverlay(summary, 12f, theme.getPlaceholderText());
        summaryText.setPosition(0, y);
        scroll.addElement(summaryText);
        y += summaryText.getHeight() + 14;

        String persona = personaField.getText().trim();
        TextFlowOverlay personaPreview = new TextFlowOverlay(persona.isEmpty() ? "No persona set." : persona, fieldWidth, 13f, theme.getPlaceholderText());
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

    private void submit() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            error = "Name is required before creating.";
            owner.renderContent();
            return;
        }
        String userName = userNameField.getText().trim();
        if (userName.isEmpty()) {
            error = "The user needs a name, fill in the User step.";
            owner.renderContent();
            return;
        }

        Character character;
        String id;
        if (editing != null) {
            character = editing;
            id = editing.getId();
        } else {
            String typedId = slugify(idField.getText().trim());
            if (typedId.isEmpty()) {
                id = uniqueId(name, candidate -> Character.exists(App.environment, candidate));
            } else if (Character.exists(App.environment, typedId)) {
                error = "That character ID is already taken.";
                owner.renderContent();
                return;
            } else {
                id = typedId;
            }
            character = new Character(App.environment, id);
        }

        character.setDisplayName(name);
        character.setPersona(personaField.getText().trim());
        character.setFirstMessage(firstMessageField.getText().trim());
        character.setChatScenario(scenarioField.getText().trim());
        character.setUserName(userName);
        character.setUserPersona(userPersonaField.getText().trim());
        character.setUserLorebook(userLore.entries());
        character.setLorebook(lore.entries());
        if (pendingIconFile != null) {
            try {
                character.setIconFile(pendingIconFile);
            } catch (IOException e) {
                App.logger.error("Could not copy icon for character '{}'", id, e);
            }
        }
        if (userIconFile != null) {
            try {
                character.setUserIconFile(userIconFile);
            } catch (IOException e) {
                App.logger.error("Could not copy the user icon for character '{}'", id, e);
            }
        } else {
            character.setUserIconPath("");
        }

        if (editing != null) {
            owner.onCharacterEdited(character);
        } else {
            owner.onCharacterCreated(character);
        }
    }
}
