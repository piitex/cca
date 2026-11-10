package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.card.CardExporter;
import me.piitex.cca.crypto.ProtectedImages;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.HubLayout;

import me.piitex.engine.input.FileDialog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

// The Characters page. Clicking a card opens the chat.
public class CharacterHub extends ItemHub<Character> {
    private final MainMenu owner;

    public CharacterHub(MainMenu owner) {
        Character.loadAll(App.environment);
        super(owner, Character.getCharacters().values().stream().toList(), HubLayout.Keys.CHARACTERS);
        this.owner = owner;
        // Decrypting the icons takes a while, so it starts now and the first draw of the hub or a chat finds them ready.
        ProtectedImages.preload(Character.getCharacters().values().stream()
                .flatMap(character -> Stream.of(Path.of(character.getIconPath()), Path.of(character.getUserIconPath())))
                .toList());
    }

    @Override
    protected String noun() {
        return "Character";
    }

    @Override
    protected void open(Character character) {
        owner.openChat(character);
    }

    @Override
    protected void edit(Character character) {
        owner.editCharacter(character);
    }

    @Override
    protected void create() {
        owner.openCreateCharacter();
    }

    @Override
    protected void duplicateItem(Character item) {
        owner.duplicateCharacter(item);
    }

    @Override
    protected boolean canExport() {
        return true;
    }

    // Saves the character as a card png, the kind other chat apps can import too.
    @Override
    protected void exportItem(Character character) {
        FileDialog.save()
                .filter("Character Card", "png")
                .defaultName(character.getId() + ".png")
                .onSelect(path -> {
                    Path file = path.getFileName().toString().toLowerCase().endsWith(".png") ? path : path.resolveSibling(path.getFileName() + ".png");
                    try {
                        CardExporter.export(character, file);
                        App.logger.info("Exported character '{}' to '{}'", character.getId(), file);
                        Components.notice("Card exported", character.getDisplayName() + " was saved to " + file + ".");
                    } catch (IOException | RuntimeException e) {
                        App.logger.error("Could not export character '{}'", character.getId(), e);
                        Components.notice("Couldn't export", "The card could not be written. Check that the folder can be saved to.");
                    }
                })
                .show();
    }

    @Override
    protected void erase(Character character) throws IOException {
        character.delete();
    }

    @Override
    protected String deleteWarning() {
        return "This removes the character and all of its chats. This can't be undone.";
    }

    @Override
    protected String emptyHint() {
        return "Create your first character to start chatting.";
    }
}
