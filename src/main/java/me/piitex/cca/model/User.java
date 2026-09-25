package me.piitex.cca.model;

import me.piitex.cca.App;
import me.piitex.cca.crypto.EncryptedConfig;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// A reusable persona for the human side of a chat: a personality and a lorebook, no chat settings. Encrypted in user.conf,
// in a folder named by scrambling the id (see PrivateFolders). A template: a character copies one into its own user fields, it isn't linked.
public class User implements HubItem {
    private final String id;
    private final Path directory;
    private final Config config;
    private final FileCrypter crypter;

    // keyword, lore entry
    private final Map<String, String> lorebook = new LinkedHashMap<>();

    private String displayName;
    private String persona;
    private String iconPath;

    public User(AppEnvironment environment, String id) {
        this.id = id;
        this.crypter = PrivateFolders.crypter(environment);
        try {
            this.directory = environment.getDirectory("users", crypter.scramble("user:" + id));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create user directory for '" + id + "'", e);
        }

        try {
            this.config = EncryptedConfig.loadOrCreate(crypter, directory.resolve("user.conf"), c -> {
                c.setDefault("id", id);
                c.setDefault("display-name", "");
                c.setDefault("persona", "");
                c.setDefault("icon-path", "");
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load user.conf for '" + id + "'", e);
        }

        displayName = config.getString("display-name", "");
        persona = config.getString("persona", "");
        iconPath = config.getString("icon-path", "");
        lorebook.putAll(Lorebook.read(config));
    }

    public static boolean exists(AppEnvironment environment, String id) {
        return Files.isDirectory(environment.getDataPath("users/" + PrivateFolders.folderName(environment, "user", id)));
    }

    // Null for a blank id or one that doesn't exist.
    public static User find(AppEnvironment environment, String id) {
        if (id == null || id.isBlank() || !exists(environment, id)) return null;
        return new User(environment, id);
    }

    public static List<User> loadAll(AppEnvironment environment) {
        List<User> users = new ArrayList<>();
        for (String id : PrivateFolders.ids(environment, "users", "user", "user.conf")) {
            users.add(new User(environment, id));
        }
        return users;
    }

    private void save() {
        try {
            EncryptedConfig.save(crypter, config);
        } catch (IOException e) {
            App.logger.error("Could not save user.conf for '{}'", id, e);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    public Path getDirectory() {
        return directory;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        config.set("display-name", displayName);
        save();
    }

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
        config.set("persona", persona);
        save();
    }

    @Override
    public String getIconPath() {
        if (iconPath == null || iconPath.isEmpty()) {
            return directory.resolve("user.png").toString();
        }
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
        config.set("icon-path", iconPath);
        save();
    }

    // Copies the image into the user's folder so it survives the original being moved.
    public void setIconFile(Path sourceFile) throws IOException {
        String name = sourceFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "png";
        Path dest = directory.resolve("icon." + extension);
        Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
        setIconPath(dest.toString());
    }

    public Map<String, String> getLorebook() {
        return Collections.unmodifiableMap(lorebook);
    }

    public void setLorebook(Map<String, String> entries) {
        lorebook.clear();
        lorebook.putAll(entries);
        Lorebook.write(config, lorebook);
        save();
    }

    // Deletes the user's folder. Don't use this instance afterwards.
    public void delete() throws IOException {
        App.logger.info("Deleting user '{}'", id);
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public User duplicate(AppEnvironment environment, String newId) throws IOException {
        User copy = new User(environment, newId);
        copy.setDisplayName(displayName);
        copy.setPersona(persona);
        copy.setLorebook(lorebook);
        Path icon = Path.of(getIconPath());
        if (Files.isRegularFile(icon)) {
            copy.setIconFile(icon);
        }
        return copy;
    }
}
