package me.piitex.cca.model;

import me.piitex.engine.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Keyword, entry pairs for characters and users. The config only holds scalar lists, so it's stored as two lists side by side.
final class Lorebook {
    private Lorebook() {
    }

    static Map<String, String> read(Config config) {
        return read(config, "");
    }

    // The prefix lets one config hold two lorebooks, a character's and its user's.
    static Map<String, String> read(Config config, String prefix) {
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> keys = config.getStringList(prefix + "lorebook-keys");
        List<String> values = config.getStringList(prefix + "lorebook-values");
        for (int i = 0; i < keys.size(); i++) {
            entries.put(keys.get(i), i < values.size() ? values.get(i) : "");
        }
        return entries;
    }

    static void write(Config config, Map<String, String> entries) {
        write(config, "", entries);
    }

    static void write(Config config, String prefix, Map<String, String> entries) {
        List<String> keys = new ArrayList<>(entries.keySet());
        config.set(prefix + "lorebook-keys", keys);
        config.set(prefix + "lorebook-values", keys.stream().map(entries::get).toList());
    }
}
