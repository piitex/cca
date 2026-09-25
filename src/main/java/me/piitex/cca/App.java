package me.piitex.cca;

import me.piitex.cca.backend.server.ServerProcess;
import me.piitex.cca.config.AppConfig;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.model.PrivateFolders;
import me.piitex.cca.theme.Themes;
import me.piitex.cca.ui.MenuManager;
import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.io.AppEnvironment;
import me.piitex.engine.ui.text.Font;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class App {
    public static Window window;
    public static Logger logger = LogManager.getLogger("CCA");
    public static AppEnvironment environment;
    public static App instance;

    private AppConfig appConfig;
    private ModelSettings modelSettings;
    private AppearanceSettings appearance;

    public App() {
        instance = this;
        initializeConfigurations();
        initializeWindow();
    }

    private void initializeWindow() {
        // Setup theme
        Themes.apply(appearance.themeId.get());

        // Setup global font
        File font = environment.getDataPath("data/fonts/Roboto-Regular.ttf").toFile();
        if (!font.exists()) extractFont(font);
        if (!font.exists()) {
            App.logger.error("Could not load default font, aborting...");
            return;
        }
        Font.setDefaultFont(font);

        window = new Window(new WindowOptions("CCA").setDimensions(appConfig.getWidth(), appConfig.getHeight()));
        applyWindowSettings();
        new MenuManager(window);

        new Engine().start(window);
    }

    // The API keys in model.conf are encrypted, so this has to wait until the app password (if there is one) has been entered.
    public void loadEncryptedSettings() {
        PrivateFolders.upgradeAll(environment);
        try {
            modelSettings = ModelSettings.load(environment, appConfig.getConfig());
        } catch (IOException e) {
            logger.error("Could not load the model settings!", e);
            return;
        }
        // Some people only run the app to host a model for their other devices.
        if (modelSettings.autoStart.get() && !modelSettings.usesCloud() && appConfig.isSetupComplete()) {
            ServerProcess.get().start();
        }
    }

    // Settings > General. Picked up on the next frame, so it's safe while running.
    public void applyWindowSettings() {
        window.setVsync(appearance.vsync.get());
        window.setMaxFPS(appearance.maxFps.get());
    }

    // The font ships inside the jar but the engine needs an actual file, so copy it out on the first run.
    private void extractFont(File target) {
        try (InputStream in = App.class.getResourceAsStream("/fonts/Roboto-Regular.ttf")) {
            if (in == null) return;
            Files.createDirectories(target.toPath().getParent());
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Could not extract the default font!", e);
        }
    }

    private void initializeConfigurations() {
        try {
            environment = AppEnvironment.builder("CCA").devDirectory(Path.of("app")).build();
            appConfig = new AppConfig(environment);
            Themes.load(environment);
            appearance = AppearanceSettings.load(environment, appConfig.getConfig());
        } catch (IOException e) {
            logger.error("Could not create application environment!", e);
        }
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public ModelSettings getModelSettings() {
        return modelSettings;
    }

    public AppearanceSettings getAppearance() {
        return appearance;
    }
}
