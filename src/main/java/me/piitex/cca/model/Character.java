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

// A character carries its own user: the name, personality, icon and lorebook of who it chats with. A user template only
// fills those in when the character is made, nothing is linked, so a character shared with someone brings its user along.
// Everything is encrypted. The character is in character.conf and each chat is its own file in chats/, both inside a folder
// named by scrambling the id (see PrivateFolders), the id itself is kept in the config.
public class Character implements HubItem {
    private final String id;
    private final Path directory;
    private final Config config;
    private final FileCrypter crypter;
    private final List<Chat> chats = new ArrayList<>();

    // keyword, lore entry
    private final Map<String, String> lorebook = new LinkedHashMap<>();

    private String displayName;
    private String persona;
    private String iconPath;
    private String firstMessage;
    private String chatScenario;
    private String userName;
    private String userPersona;
    private String userIconPath;
    private final Map<String, String> userLorebook = new LinkedHashMap<>();
    private boolean shownDisclaimer;

    // Loads the character if it exists, otherwise creates the folders and a default character.conf.
    public Character(AppEnvironment environment, String id) {
        this.id = id;
        this.crypter = PrivateFolders.crypter(environment);
        String folder = crypter.scramble("character:" + id);
        try {
            this.directory = environment.getDirectory("characters", folder);
            environment.getDirectory("characters", folder, "chats");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create character directory for '" + id + "'", e);
        }

        try {
            this.config = EncryptedConfig.loadOrCreate(crypter, directory.resolve("character.conf"), c -> {
                c.setDefault("id", id);
                c.setDefault("display-name", "");
                c.setDefault("persona", "");
                c.setDefault("icon-path", "");
                c.setDefault("first-message", "");
                c.setDefault("chat-scenario", "");
                c.setDefault("user-name", "");
                c.setDefault("user-persona", "");
                c.setDefault("user-icon-path", "");
                c.setDefault("shown-disclaimer", false);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load character.conf for '" + id + "'", e);
        }

        displayName = config.getString("display-name", "");
        persona = config.getString("persona", "");
        iconPath = config.getString("icon-path", "");
        firstMessage = config.getString("first-message", "");
        chatScenario = config.getString("chat-scenario", "");
        userName = config.getString("user-name", "");
        userPersona = config.getString("user-persona", "");
        userIconPath = config.getString("user-icon-path", "");
        userLorebook.putAll(Lorebook.read(config, "user-"));
        shownDisclaimer = config.getBoolean("shown-disclaimer", false);

        lorebook.putAll(Lorebook.read(config));

        moveLinkedUser(environment);
        loadChats();
    }

    // Characters made when users were linked by id keep a copy of that user from now on.
    private void moveLinkedUser(AppEnvironment environment) {
        String linked = config.getString("user-id", "");
        if (!config.has("user-id")) return;
        User template = userName.isEmpty() ? User.find(environment, linked) : null;
        if (template != null) {
            fillUser(template);
            App.logger.info("Copied the linked user into character '{}'", id);
        }
        config.remove("user-id");
        save();
    }

    private void loadChats() {
        Path chatDirectory = getChatDirectory();
        if (!Files.isDirectory(chatDirectory)) return;
        try (Stream<Path> entries = Files.list(chatDirectory)) {
            for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) {
                chats.add(new Chat(file, crypter));
            }
        } catch (IOException e) {
            App.logger.error("Could not list chats for character '{}'", id, e);
        }
    }

    // The constructor always creates, so check this before making a character with a picked id.
    public static boolean exists(AppEnvironment environment, String id) {
        return Files.isDirectory(environment.getDataPath("characters/" + PrivateFolders.folderName(environment, "character", id)));
    }

    public static List<Character> loadAll(AppEnvironment environment) {
        List<Character> characters = new ArrayList<>();
        for (String id : PrivateFolders.ids(environment, "characters", "character", "character.conf")) {
            characters.add(new Character(environment, id));
        }
        App.logger.info("Loaded {} characters.", characters.size());
        return characters;
    }

    private void save() {
        try {
            EncryptedConfig.save(crypter, config);
        } catch (IOException e) {
            App.logger.error("Could not save character.conf for '{}'", id, e);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    public Path getDirectory() {
        return directory;
    }

    public Path getChatDirectory() {
        return directory.resolve("chats");
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
            return directory.resolve("character.png").toString();
        }
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
        config.set("icon-path", iconPath);
        save();
    }

    // Copies the image into the character's folder so the icon survives the original being moved.
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

    public String getFirstMessage() {
        return firstMessage;
    }

    public void setFirstMessage(String firstMessage) {
        this.firstMessage = firstMessage;
        config.set("first-message", firstMessage);
        save();
    }

    public String getChatScenario() {
        return chatScenario;
    }

    public void setChatScenario(String chatScenario) {
        this.chatScenario = chatScenario;
        config.set("chat-scenario", chatScenario);
        save();
    }

    // The person the character chats with. Empty name means none was set, chats fall back to "You".
    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
        config.set("user-name", userName);
        save();
    }

    public String getUserPersona() {
        return userPersona;
    }

    public void setUserPersona(String userPersona) {
        this.userPersona = userPersona;
        config.set("user-persona", userPersona);
        save();
    }

    public String getUserIconPath() {
        if (userIconPath == null || userIconPath.isEmpty()) {
            return directory.resolve("user.png").toString();
        }
        return userIconPath;
    }

    public void setUserIconPath(String userIconPath) {
        this.userIconPath = userIconPath;
        config.set("user-icon-path", userIconPath);
        save();
    }

    // Copies the image into the character's folder, next to the character's own icon.
    public void setUserIconFile(Path sourceFile) throws IOException {
        String name = sourceFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "png";
        Path dest = directory.resolve("user-icon." + extension);
        Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
        setUserIconPath(dest.toString());
    }

    public Map<String, String> getUserLorebook() {
        return Collections.unmodifiableMap(userLorebook);
    }

    public void setUserLorebook(Map<String, String> entries) {
        userLorebook.clear();
        userLorebook.putAll(entries);
        Lorebook.write(config, "user-", userLorebook);
        save();
    }

    public boolean hasUser() {
        return !userName.isBlank();
    }

    // Copies everything from a template.
    public void fillUser(User template) {
        userName = template.getDisplayName();
        userPersona = template.getPersona();
        userLorebook.clear();
        userLorebook.putAll(template.getLorebook());
        config.set("user-name", userName);
        config.set("user-persona", userPersona);
        Lorebook.write(config, "user-", userLorebook);
        Path icon = Path.of(template.getIconPath());
        if (Files.isRegularFile(icon)) {
            try {
                setUserIconFile(icon);
            } catch (IOException e) {
                App.logger.error("Could not copy the user icon for character '{}'", id, e);
            }
        }
        save();
    }

    public boolean isShownDisclaimer() {
        return shownDisclaimer;
    }

    public void setShownDisclaimer(boolean shownDisclaimer) {
        this.shownDisclaimer = shownDisclaimer;
        config.set("shown-disclaimer", shownDisclaimer);
        save();
    }

    public List<Chat> getChats() {
        return List.copyOf(chats);
    }

    public Chat createChat(String name) {
        Chat chat = new Chat(getChatDirectory().resolve(name + ".dat"), crypter);
        chats.add(chat);
        return chat;
    }

    // A chat that was never saved has no file, that's fine.
    public void deleteChat(Chat chat) throws IOException {
        Files.deleteIfExists(chat.getFile());
        chats.remove(chat);
    }

    // Deletes the whole character folder including every chat. Don't use this instance afterwards.
    public void delete() throws IOException {
        App.logger.info("Deleting character '{}'", id);
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // Chats are not copied, a duplicate starts fresh.
    public Character duplicate(AppEnvironment environment, String newId) throws IOException {
        Character copy = new Character(environment, newId);
        copy.setDisplayName(displayName);
        copy.setPersona(persona);
        copy.setFirstMessage(firstMessage);
        copy.setChatScenario(chatScenario);
        copy.setUserName(userName);
        copy.setUserPersona(userPersona);
        copy.setUserLorebook(userLorebook);
        Path userIcon = Path.of(getUserIconPath());
        if (Files.isRegularFile(userIcon)) {
            copy.setUserIconFile(userIcon);
        }
        copy.setLorebook(lorebook);
        Path icon = Path.of(getIconPath());
        if (Files.isRegularFile(icon)) {
            copy.setIconFile(icon);
        }
        return copy;
    }
}
