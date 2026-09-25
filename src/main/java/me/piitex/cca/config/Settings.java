package me.piitex.cca.config;

import me.piitex.engine.config.Config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for a group of saved options (ModelSettings, AppearanceSettings). Each option is a {@link Setting}
 * registered under a dotted key like "sampling.temperature". The first part of the key is the tab it
 * belongs to, which is what "Reset tab" uses.
 */
public abstract class Settings {

    public final class Setting<T> {
        private final String key;
        private final T defaultValue;
        private T value;

        private Setting(String key, T defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
            settings.put(key, this);
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value == null ? defaultValue : value;
        }

        public T getDefault() {
            return defaultValue;
        }

        public boolean isDefault() {
            return defaultValue.equals(value);
        }

        public void reset() {
            value = defaultValue;
        }

        public String key() {
            return key;
        }
    }

    private final Map<String, Setting<?>> settings = new LinkedHashMap<>();

    // Null for the unsaved copies the settings screens edit.
    protected Config config;

    @SuppressWarnings("unchecked")
    protected final void copyValuesFrom(Settings other) {
        for (Map.Entry<String, Setting<?>> entry : other.settings.entrySet()) {
            ((Setting<Object>) settings.get(entry.getKey())).set(entry.getValue().get());
        }
    }

    protected final boolean valuesDifferFrom(Settings other) {
        for (Map.Entry<String, Setting<?>> entry : settings.entrySet()) {
            if (!entry.getValue().get().equals(other.settings.get(entry.getKey()).get())) return true;
        }
        return false;
    }

    // The current values of the keys that start with prefix, in the order they were registered.
    protected final Map<String, Object> valuesWithPrefix(String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Setting<?> setting : settings.values()) {
            if (setting.key().startsWith(prefix)) values.put(setting.key(), setting.get());
        }
        return values;
    }

    // Keys this doesn't have, or values of the wrong type, are skipped.
    @SuppressWarnings("unchecked")
    protected final void putValues(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Setting<Object> setting = (Setting<Object>) settings.get(entry.getKey());
            if (setting != null && setting.getDefault().getClass().isInstance(entry.getValue())) setting.set(entry.getValue());
        }
    }

    protected final void resetValues() {
        for (Setting<?> setting : settings.values()) {
            setting.reset();
        }
    }

    // Only resets the keys that start with one of the prefixes, e.g. "sampling."
    protected final void resetValues(String... prefixes) {
        for (Setting<?> setting : settings.values()) {
            for (String prefix : prefixes) {
                if (setting.key().startsWith(prefix)) {
                    setting.reset();
                    break;
                }
            }
        }
    }

    // Anything missing from the file keeps its default.
    @SuppressWarnings("unchecked")
    protected final void readValues(Config source) {
        for (Setting<?> setting : settings.values()) {
            if (!source.has(setting.key())) continue;
            Setting<Object> target = (Setting<Object>) setting;
            Object def = setting.getDefault();
            if (def instanceof Integer) {
                target.set(source.getInt(setting.key(), (Integer) def));
            } else if (def instanceof Double) {
                target.set(source.getDouble(setting.key(), (Double) def));
            } else if (def instanceof Boolean) {
                target.set(source.getBoolean(setting.key(), (Boolean) def));
            } else {
                target.set(source.getString(setting.key(), (String) def));
            }
        }
    }

    protected final void writeValues(Config target) {
        for (Setting<?> setting : settings.values()) {
            target.set(setting.key(), setting.get());
        }
    }

    protected final Setting<String> str(String key, String def) {
        return new Setting<>(key, def);
    }

    protected final Setting<Integer> num(String key, int def) {
        return new Setting<>(key, def);
    }

    protected final Setting<Double> dec(String key, double def) {
        return new Setting<>(key, def);
    }

    protected final Setting<Boolean> bool(String key, boolean def) {
        return new Setting<>(key, def);
    }
}
