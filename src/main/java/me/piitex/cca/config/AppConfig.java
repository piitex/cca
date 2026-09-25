package me.piitex.cca.config;

import me.piitex.cca.App;
import me.piitex.cca.crypto.EncryptedConfig;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.model.HubLayout;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AppConfig {
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private String theme = "dark";
    private boolean setupComplete = false;
    private long serverPid = 0;

    private Config config;
    private final AppEnvironment environment;
    // Folder names and character order. Encrypted, so it's only loaded once the app is unlocked.
    private Config layout;
    private FileCrypter layoutCrypter;

    public AppConfig(AppEnvironment environment) {
        this.environment = environment;
        try {
            config = environment.loadOrCreateConfig("data/app.conf", c -> {
                c.setDefault("width", width);
                c.setDefault("height", height);
                c.setDefault("theme", theme);
                c.setDefault("setupComplete", setupComplete);
                c.setDefault("serverPid", serverPid);
            });
            width = config.getInt("width", width);
            height = config.getInt("height", height);
            theme = config.getString("theme", theme);
            setupComplete = config.getBoolean("setupComplete", setupComplete);
            serverPid = config.getLong("serverPid", serverPid);
        } catch (IOException e) {
            App.logger.error("Could not create app.conf!", e);
        }
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
        config.set("width", width);
        save();
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
        config.set("height", height);
        save();
    }

    // Old installs kept the theme here (dark or light). It's only read once to seed AppearanceSettings.
    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
        config.set("theme", theme);
        save();
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }

    public void setSetupComplete(boolean setupComplete) {
        this.setupComplete = setupComplete;
        config.set("setupComplete", setupComplete);
        save();
    }

    // The last llama-server we started. Saved so a server left behind by a crash can be shut down on the next launch.
    public long getServerPid() {
        return serverPid;
    }

    public void setServerPid(long serverPid) {
        this.serverPid = serverPid;
        config.set("serverPid", serverPid);
        save();
    }

    // The hubs' folders and card order are kept in layout.conf, encrypted.
    public HubLayout.Storage layoutStorage() {
        Config layout = layout();
        return new HubLayout.Storage() {
            @Override
            public List<String> list(String key) {
                return layout.getStringList(key);
            }

            @Override
            public void putList(String key, List<String> values) {
                layout.set(key, List.copyOf(values));
            }

            @Override
            public String string(String key) {
                return layout.getString(key, null);
            }

            @Override
            public void putString(String key, String value) {
                layout.set(key, value);
            }

            @Override
            public void remove(String key) {
                layout.remove(key);
            }

            @Override
            public void flush() {
                try {
                    EncryptedConfig.save(layoutCrypter, layout);
                } catch (IOException e) {
                    App.logger.error("Could not save layout.conf!", e);
                }
            }
        };
    }

    private Config layout() {
        if (layout != null) return layout;
        try {
            layoutCrypter = FileCrypter.forEnvironment(environment);
            Path file = environment.getDataPath("data/layout.conf");
            boolean existed = Files.exists(file);
            layout = EncryptedConfig.loadOrCreate(layoutCrypter, file, c -> {
            });
            if (!existed) moveOldLayout();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load layout.conf", e);
        }
        return layout;
    }

    // Older installs kept the layout in app.conf, which isn't encrypted. Moves it over the first time.
    private void moveOldLayout() throws IOException {
        if (!config.has("characterOrder") && !config.has("folders") && !config.has("folder")) return;
        layout.set("characterOrder", config.getStringList("characterOrder"));
        List<String> folders = config.getStringList("folders");
        layout.set("folders", folders);
        for (String id : folders) {
            layout.set("folder." + id + ".name", config.getString("folder." + id + ".name", "Folder"));
            layout.set("folder." + id + ".members", config.getStringList("folder." + id + ".members"));
        }
        EncryptedConfig.save(layoutCrypter, layout);
        config.remove("characterOrder");
        config.remove("folders");
        config.remove("folder");
        save();
        App.logger.info("Moved the hub layout into an encrypted file.");
    }

    public Config getConfig() {
        return config;
    }

    private void save() {
        try {
            config.save();
        } catch (IOException e) {
            App.logger.error("Could not save app.conf!", e);
        }
    }
}
