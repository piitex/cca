package me.piitex.cca.model;

import me.piitex.cca.App;
import me.piitex.cca.crypto.EncryptedConfig;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.crypto.ProtectedImages;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

// A character carries its own user: the name, personality, icon and lorebook of who it chats with. A user template only
// fills those in when the character is made, nothing is linked, so a character shared with someone brings its user along.
// Everything is encrypted. The character is in character.conf and each chat is its own file in chats/, both inside a folder
// named by scrambling the id (see PrivateFolders), the id itself is kept in the config.
public class Character implements HubItem {
    static final String ICON_FILE = "icon.enc";
    static final String USER_ICON_FILE = "user-icon.enc";
    static final String BACKGROUND_FILE = "background.enc";

    private final String id;
    private final Path directory;
    private final Config config;
    private final FileCrypter crypter;
    private final List<Chat> chats = new ArrayList<>();

    // keyword, lore entry
    private final Map<String, String> lorebook = new LinkedHashMap<>();
    // name, value. [name] in the chat or prompt turns into the value, see PromptBuilder.format.
    private final Map<String, String> placeholders = new LinkedHashMap<>();

    private String displayName;
    private String persona;
    private String iconPath;
    // The picture behind this character's chats, blank to use the one from Settings > Chat.
    private String backgroundPath;
    private String firstMessage;
    private String chatScenario;
    private String userName;
    private String userPersona;
    private String userIconPath;
    private final Map<String, String> userLorebook = new LinkedHashMap<>();
    private final Map<String, String> userPlaceholders = new LinkedHashMap<>();
    private boolean shownDisclaimer;
    // An example reply the character's replies follow, like {{char}}: "What they say." *What they do.* Blank for none.
    // Strict makes the local model follow it with a grammar, see DialogueFormat.
    private String replyFormat;
    private boolean strictFormat;

    private static final TreeMap<String, Character> characters = new TreeMap<>();

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
                c.setDefault("background-path", "");
                c.setDefault("first-message", "");
                c.setDefault("chat-scenario", "");
                c.setDefault("user-name", "");
                c.setDefault("user-persona", "");
                c.setDefault("user-icon-path", "");
                c.setDefault("shown-disclaimer", false);
                c.setDefault("reply-format", "");
                c.setDefault("strict-format", false);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load character.conf for '" + id + "'", e);
        }

        displayName = config.getString("display-name", "");
        persona = config.getString("persona", "");
        iconPath = config.getString("icon-path", "");
        backgroundPath = config.getString("background-path", "");
        firstMessage = config.getString("first-message", "");
        chatScenario = config.getString("chat-scenario", "");
        userName = config.getString("user-name", "");
        userPersona = config.getString("user-persona", "");
        userIconPath = config.getString("user-icon-path", "");
        userLorebook.putAll(Lorebook.read(config, "user-"));
        userPlaceholders.putAll(Lorebook.readPairs(config, "user-placeholder"));
        shownDisclaimer = config.getBoolean("shown-disclaimer", false);
        replyFormat = config.getString("reply-format", "");
        strictFormat = config.getBoolean("strict-format", false);

        lorebook.putAll(Lorebook.read(config));
        placeholders.putAll(Lorebook.readPairs(config, "placeholder"));

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
                chats.add(new Chat(this, file, crypter));
            }
        } catch (IOException e) {
            App.logger.error("Could not list chats for character '{}'", id, e);
        }
    }

    // The constructor always creates, so check this before making a character with a picked id.
    public static boolean exists(AppEnvironment environment, String id) {
        return Files.isDirectory(environment.getDataPath("characters/" + PrivateFolders.folderName(environment, "character", id)));
    }

    public static void loadAll(AppEnvironment environment) {
        for (String id : PrivateFolders.ids(environment, "characters", "character", "character.conf")) {
            characters.put(id, new Character(environment, id));
        }
        App.logger.info("Loaded {} characters.", characters.size());
    }

    public static Character getCharacter(String id) {
        return characters.get(id);
    }

    public static TreeMap<String, Character> getCharacters() {
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

    // Stores the image encrypted in the character's folder so the icon survives the original being moved.
    public void setIconFile(Path sourceFile) throws IOException {
        Path dest = directory.resolve(ICON_FILE);
        ProtectedImages.write(crypter, dest, sourceFile);
        setIconPath(dest.toString());
    }

    public void clearIcon() throws IOException {
        Files.deleteIfExists(Path.of(getIconPath()));
        setIconPath("");
    }

    // The file of this character's own chat background, or null when it uses the one from Settings.
    public Path getBackgroundFile() {
        if (backgroundPath == null || backgroundPath.isEmpty()) return null;
        Path file = Path.of(backgroundPath);
        // The data folder may have moved since it was saved, the file's name is all that's needed to find it again.
        return Files.isRegularFile(file) ? file : directory.resolve(file.getFileName());
    }

    // Stores the image encrypted in the character's folder, like the icon.
    public void setBackgroundFile(Path sourceFile) throws IOException {
        Path dest = directory.resolve(BACKGROUND_FILE);
        ProtectedImages.write(crypter, dest, sourceFile);
        setBackgroundPath(dest.toString());
    }

    public void clearBackground() throws IOException {
        Path file = getBackgroundFile();
        if (file != null) Files.deleteIfExists(file);
        setBackgroundPath("");
    }

    private void setBackgroundPath(String backgroundPath) {
        this.backgroundPath = backgroundPath;
        config.set("background-path", backgroundPath);
        save();
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

    public Map<String, String> getPlaceholders() {
        return Collections.unmodifiableMap(placeholders);
    }

    public void setPlaceholders(Map<String, String> entries) {
        placeholders.clear();
        placeholders.putAll(entries);
        Lorebook.writePairs(config, "placeholder", placeholders);
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

    // Stores the image encrypted in the character's folder, next to the character's own icon.
    public void setUserIconFile(Path sourceFile) throws IOException {
        Path dest = directory.resolve(USER_ICON_FILE);
        ProtectedImages.write(crypter, dest, sourceFile);
        setUserIconPath(dest.toString());
    }

    public void clearUserIcon() throws IOException {
        Files.deleteIfExists(Path.of(getUserIconPath()));
        setUserIconPath("");
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

    public Map<String, String> getUserPlaceholders() {
        return Collections.unmodifiableMap(userPlaceholders);
    }

    public void setUserPlaceholders(Map<String, String> entries) {
        userPlaceholders.clear();
        userPlaceholders.putAll(entries);
        Lorebook.writePairs(config, "user-placeholder", userPlaceholders);
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
        userPlaceholders.clear();
        userPlaceholders.putAll(template.getPlaceholders());
        config.set("user-name", userName);
        config.set("user-persona", userPersona);
        Lorebook.write(config, "user-", userLorebook);
        Lorebook.writePairs(config, "user-placeholder", userPlaceholders);
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

    public String getReplyFormat() {
        return replyFormat;
    }

    public void setReplyFormat(String replyFormat) {
        this.replyFormat = replyFormat;
        config.set("reply-format", replyFormat);
        save();
    }

    public boolean isStrictFormat() {
        return strictFormat;
    }

    public void setStrictFormat(boolean strictFormat) {
        this.strictFormat = strictFormat;
        config.set("strict-format", strictFormat);
        save();
    }

    public List<Chat> getChats() {
        return List.copyOf(chats);
    }

    public Chat createChat(Character character, String name) {
        Chat chat = new Chat(character, getChatDirectory().resolve(name + ".dat"), crypter);
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
}
