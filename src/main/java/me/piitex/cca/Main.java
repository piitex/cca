package me.piitex.cca;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Main {

    static void main(String[] args) {
        if (relaunchOnFirstThread(args)) return;
        new App();
    }

    // GLFW needs the first thread on macOS, and java -jar can't ask for that from inside the jar. So start a second
    // JVM that has it. The jpackage launcher already runs the app that way, and so does anything started with
    // -XstartOnFirstThread, both are left alone.
    private static boolean relaunchOnFirstThread(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        if (System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid()) != null) return false;
        if (System.getProperty("jpackage.app-path") != null) return false;

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XstartOnFirstThread");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--sun-misc-unsafe-memory-access=deny"); // Same options as the jpackage build.
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments()); // -D options and the like
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(args));

        try {
            System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
        } catch (IOException e) {
            System.err.println("Could not restart with -XstartOnFirstThread: " + e.getMessage());
            return false; // Try to run here anyway.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }
}
