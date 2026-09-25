package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.HubLayout;

import java.io.IOException;

// The Characters page. Clicking a card opens the chat.
public class CharacterHub extends ItemHub<Character> {
    private final MainMenu owner;

    public CharacterHub(MainMenu owner) {
        super(owner, Character.loadAll(App.environment), HubLayout.Keys.CHARACTERS);
        this.owner = owner;
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
    protected boolean idTaken(String id) {
        return Character.exists(App.environment, id);
    }

    @Override
    protected Character copy(Character character, String newId) throws IOException {
        return character.duplicate(App.environment, newId);
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
