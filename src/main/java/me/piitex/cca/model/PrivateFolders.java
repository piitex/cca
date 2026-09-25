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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// Where characters and users live on disk. The folder is named by scrambling the id with the key, so the names people
// gave things aren't readable from the file system. The id itself is kept inside the encrypted config file.
public final class PrivateFolders {
    private PrivateFolders() {
    }

    // Encrypts characters and users saved by older versions. The hubs only open them when they're shown,
    // so this runs at startup to make sure none are left as plain text.
    public static void upgradeAll(AppEnvironment environment) {
        ids(environment, "characters", "character", "character.conf");
        ids(environment, "users", "user", "user.conf");
    }

    static FileCrypter crypter(AppEnvironment environment) {
        try {
            return FileCrypter.forEnvironment(environment);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not set up encryption", e);
        }
    }

    // characters/<scrambled id>
    static String folderName(AppEnvironment environment, String kind, String id) {
        return crypter(environment).scramble(kind + ":" + id);
    }

    // Every id under the root, read from the encrypted config in each folder. Older folders named by their id are renamed first.
    static List<String> ids(AppEnvironment environment, String root, String kind, String configName) {
        FileCrypter crypter = crypter(environment);
        List<String> ids = new ArrayList<>();
        Path rootPath;
        try {
            rootPath = environment.getDirectory(root);
        } catch (IOException e) {
            App.logger.error("Could not access the {} directory!", root, e);
            return ids;
        }
        try (Stream<Path> entries = Files.list(rootPath)) {
            for (Path folder : entries.filter(Files::isDirectory).sorted().toList()) {
                try {
                    Path config = folder.resolve(configName);
                    if (!Files.isRegularFile(config)) continue;
                    if (!EncryptedConfig.isEncrypted(config)) folder = upgrade(crypter, rootPath, folder, kind, configName);
                    if (folder == null) continue;
                    Config loaded = EncryptedConfig.loadOrCreate(crypter, folder.resolve(configName), c -> {
                    });
                    String id = loaded.getString("id", "");
                    if (id.isEmpty() || !folder.getFileName().toString().equals(crypter.scramble(kind + ":" + id))) {
                        App.logger.warn("Skipping a {} folder that doesn't match its id.", kind);
                        continue;
                    }
                    ids.add(id);
                } catch (IOException | RuntimeException e) {
                    App.logger.error("Could not read a {} folder", kind, e);
                }
            }
        } catch (IOException e) {
            App.logger.error("Could not list the {} directory!", root, e);
        }
        return ids;
    }

    // A folder from before names were scrambled: the folder name is the id and the config is plain text.
    // Renames it and encrypts the config, with the id added. Returns the new folder, or null if it couldn't be done.
    private static Path upgrade(FileCrypter crypter, Path rootPath, Path folder, String kind, String configName) throws IOException {
        String id = folder.getFileName().toString();
        Path target = rootPath.resolve(crypter.scramble(kind + ":" + id));
        if (Files.exists(target)) {
            App.logger.warn("Could not upgrade a {} folder, the new name is taken.", kind);
            return null;
        }
        Files.move(folder, target);
        Config config = Config.load(target.resolve(configName));
        config.set("id", id);
        EncryptedConfig.save(crypter, config);
        App.logger.info("Encrypted a {}.", kind);
        return target;
    }
}
