package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.crypto.ProtectedImages;
import me.piitex.cca.model.HubLayout;
import me.piitex.cca.model.User;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

// The Users page. Users can't be chatted with, so clicking a card edits it.
public class UserHub extends ItemHub<User> {
    private final MainMenu owner;

    public UserHub(MainMenu owner) {
        List<User> users = User.loadAll(App.environment);
        super(owner, users, HubLayout.Keys.USERS);
        this.owner = owner;
        // Decrypting the icons takes a while, so it starts now and the first draw finds them ready.
        ProtectedImages.preload(users.stream().map(user -> Path.of(user.getIconPath())).toList());
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
    protected void duplicateItem(User item) {
        owner.duplicateUser(item);
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
