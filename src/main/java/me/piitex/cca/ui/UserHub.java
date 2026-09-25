package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.model.HubLayout;
import me.piitex.cca.model.User;

import java.io.IOException;

// The Users page. Users can't be chatted with, so clicking a card edits it.
public class UserHub extends ItemHub<User> {
    private final MainMenu owner;

    public UserHub(MainMenu owner) {
        super(owner, User.loadAll(App.environment), HubLayout.Keys.USERS);
        this.owner = owner;
    }

    @Override
    protected String noun() {
        return "User";
    }

    @Override
    protected void open(User user) {
        owner.editUser(user);
    }

    @Override
    protected void edit(User user) {
        owner.editUser(user);
    }

    @Override
    protected void create() {
        owner.openCreateUser();
    }

    @Override
    protected boolean idTaken(String id) {
        return User.exists(App.environment, id);
    }

    @Override
    protected User copy(User user, String newId) throws IOException {
        return user.duplicate(App.environment, newId);
    }

    @Override
    protected void erase(User user) throws IOException {
        user.delete();
    }

    @Override
    protected String deleteWarning() {
        return "This removes the user. This can't be undone.";
    }

    @Override
    protected String emptyHint() {
        return "Create a user to describe the human side of a chat.";
    }
}
