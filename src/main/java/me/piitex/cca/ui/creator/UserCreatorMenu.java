package me.piitex.cca.ui.creator;

import com.drew.imaging.ImageProcessingException;
import me.piitex.cca.App;
import me.piitex.cca.card.CardImporter;
import me.piitex.cca.model.User;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Creates a user, or edits one when a user is passed in. Same as the character creator without the chat setup.
// Steps: details, lorebook, finish.
public class UserCreatorMenu extends CreatorMenu {
    private static final String[] STEPS = {"User", "Lorebook", "Finish"};
    private static final int DETAILS = 0, LORE = 1;

    private TextFieldOverlay nameField;
    private TextFieldOverlay idField;
    private TextAreaOverlay personaField;
    private final LorebookStep lore;

    // The id follows the name until they type in the id field themselves.
    private boolean idEdited = false;

    // Null for a new user. When editing, the id is locked since the user's folder is named after it.
    private final User editing;

    public UserCreatorMenu(MainMenu owner) {
        this(owner, null);
    }

    public UserCreatorMenu(MainMenu owner, User editing) {
        super(owner);
        this.editing = editing;
        this.lore = new LorebookStep(this, owner);
        initFields();
        if (editing != null) {
            nameField.setText(editing.getDisplayName());
            idField.setText(editing.getId());
            idField.setReadOnly(true);
            idEdited = true;
            personaField.setText(editing.getPersona());
            lore.entries().putAll(editing.getLorebook());
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
        return isEditing() ? "Edit User" : "New User";
    }

    @Override
    protected String[] stepLabels() {
        return STEPS;
    }

    // The lorebook is optional, only the name is required.
    @Override
    protected boolean isStepValid(int step) {
        return step != DETAILS || !nameField.getText().trim().isEmpty();
    }

    @Override
    protected Element buildStep(int step, double width, double height) {
        return switch (step) {
            case DETAILS -> buildDetailsStep(width, height);
            case LORE -> lore.build(width, height);
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
        nameField.setPlaceholder("e.g. Alex");
        nameField.onTextChanged(text -> {
            error = null;
            // setText doesn't fire idField's listener, so this can't flip idEdited on its own.
            if (!idEdited) {
                idField.setText(slugify(text));
            }
            highlightSteps();
        });

        personaField = new TextAreaOverlay(10, 200);
        personaField.setPlaceholder("Personality, appearance, how you come across...");
    }

    private Element buildDetailsStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);

        double y = 0;
        // Importing replaces everything in the form, which is wrong when editing.
        if (!isEditing()) {
            y = placeImportButton(scroll, y, fieldWidth, this::pickUserCard);
        }
        y = placeField(scroll, "Name", nameField, y, fieldWidth, 36);
        y = placeField(scroll, "User ID", idField, y, fieldWidth, 36);
        y = placeIconField(scroll, y, fieldWidth);
        placeField(scroll, "Personality", personaField, y, fieldWidth, 200);
        return scroll;
    }

    // Fills the form from a SillyTavern user card. The card image becomes the icon.
    private void pickUserCard() {
        FileDialog.open()
                .filter("User Card", "png")
                .onSelect(path -> {
                    try {
                        CardImporter.CardData data = CardImporter.readUserCard(path.toFile());
                        if (!data.displayName().isEmpty()) {
                            nameField.setText(data.displayName());
                            // setText doesn't fire the name listener, so the id has to be filled here.
                            if (!idEdited) idField.setText(slugify(data.displayName()));
                        }
                        if (!data.persona().isEmpty()) personaField.setText(data.persona());
                        if (!data.lorebook().isEmpty()) {
                            lore.entries().clear();
                            lore.entries().putAll(data.lorebook());
                        }
                        pendingIconFile = path;
                        error = null;
                        App.logger.info("Imported user card '{}'", path.getFileName());
                    } catch (ImageProcessingException | IOException e) {
                        App.logger.error("Could not import user card '{}'", path, e);
                        error = "Couldn't read that file as a user card.";
                    }
                    owner.renderContent();
                })
                .show();
    }

    private Element buildFinishStep(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = fieldWidth(scroll, width);
        double y = 0;

        String name = nameField.getText().trim();
        TextOverlay nameText = new TextOverlay("Name: " + (name.isEmpty() ? "(not set)" : name), 15f, theme.getText());
        nameText.setPosition(0, y);
        scroll.addElement(nameText);
        y += nameText.getHeight() + 8;

        int entries = lore.entries().size();
        String summary = (pendingIconFile != null ? "Icon set" : "No icon") + " · "
                + entries + (entries == 1 ? " lorebook entry" : " lorebook entries");
        TextOverlay summaryText = new TextOverlay(summary, 12f, theme.getPlaceholderText());
        summaryText.setPosition(0, y);
        scroll.addElement(summaryText);
        y += summaryText.getHeight() + 14;

        String persona = personaField.getText().trim();
        TextFlowOverlay personaPreview = new TextFlowOverlay(persona.isEmpty() ? "No personality set." : persona, fieldWidth, 13f, theme.getPlaceholderText());
        personaPreview.setPosition(0, y);
        scroll.addElement(personaPreview);
        y += personaPreview.getHeight() + 20;

        ButtonOverlay create = Components.accentButton(isEditing() ? "Save Changes" : "Create User", 15f, 200, 44);
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

        User user;
        String id;
        if (editing != null) {
            user = editing;
            id = editing.getId();
        } else {
            String typedId = slugify(idField.getText().trim());
            if (typedId.isEmpty()) {
                id = uniqueId(name, candidate -> User.exists(App.environment, candidate));
            } else if (User.exists(App.environment, typedId)) {
                error = "That user ID is already taken.";
                owner.renderContent();
                return;
            } else {
                id = typedId;
            }
            user = new User(App.environment, id);
        }

        user.setDisplayName(name);
        user.setPersona(personaField.getText().trim());
        user.setLorebook(lore.entries());
        if (pendingIconFile != null) {
            try {
                user.setIconFile(pendingIconFile);
            } catch (IOException e) {
                App.logger.error("Could not copy icon for user '{}'", id, e);
            }
        }

        if (editing != null) {
            owner.onUserEdited(user);
        } else {
            owner.onUserCreated(user);
        }
    }
}
