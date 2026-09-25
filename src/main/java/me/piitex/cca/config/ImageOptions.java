package me.piitex.cca.config;

import me.piitex.cca.config.Settings.Setting;

// The settings of one picture, so the background and the chat window share the code that shows and edits them.
public record ImageOptions(Setting<String> path, Setting<String> fit, Setting<Double> zoom, Setting<Double> alignX,
                           Setting<Double> alignY, Setting<Double> opacity, Setting<String> overlay) {
}
