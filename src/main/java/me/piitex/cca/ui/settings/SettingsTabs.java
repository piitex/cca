package me.piitex.cca.ui.settings;

import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

import java.util.function.Consumer;

import static me.piitex.cca.ui.settings.SettingsUi.TAB_HEIGHT;

// The row of tabs at the top of a settings screen.
final class SettingsTabs {

    interface Tab {
        String label();

        IconAsset icon();

        // The setting key prefixes "Reset tab" resets.
        String[] settingPrefixes();
    }

    private SettingsTabs() {
    }

    // When the labels don't all fit only the active tab keeps its label, the rest are just icons.
    static <T extends Tab> Container bar(T[] tabs, T active, double width, Consumer<T> onSelect) {
        Theme theme = Components.theme();
        Container row = Components.transparent(width, TAB_HEIGHT);

        double iconSize = 16, padX = 14, gap = 8, spacing = 6;
        double full = 0;
        for (T tab : tabs) {
            full += padX * 2 + iconSize + gap + new TextOverlay(tab.label(), 14f, theme.getText()).getWidth() + spacing;
        }
        boolean compact = full > width;

        double x = 0;
        for (T tab : tabs) {
            boolean isActive = tab == active;
            boolean showLabel = isActive || !compact;
            Color tint = isActive ? Palette.accent() : theme.getPlaceholderText();
            Runnable select = () -> onSelect.accept(tab);

            TextOverlay label = new TextOverlay(tab.label(), 14f, tint);
            double pillWidth = padX * 2 + iconSize + (showLabel ? gap + label.getWidth() : 0);

            Container pill = new Container(pillWidth, TAB_HEIGHT);
            pill.getStyling().setBackgroundColor(isActive ? Palette.tint() : Color.TRANSPARENT);
            pill.getStyling().setHoverColor(isActive ? Palette.tintStrong() : theme.getButtonHoverBackground());
            pill.getStyling().setBorderThickness(0f);
            pill.getStyling().setCornerRadius(12f);
            SettingsUi.clickable(pill, select);

            IconOverlay icon = new IconOverlay(tab.icon(), iconSize, iconSize);
            icon.setTint(tint);
            icon.setPosition(padX, (TAB_HEIGHT - iconSize) / 2.0);
            SettingsUi.clickable(icon, select);
            pill.addElement(icon);

            if (showLabel) {
                label.setPosition(padX + iconSize + gap, (TAB_HEIGHT - label.getHeight()) / 2.0);
                SettingsUi.clickable(label, select);
                pill.addElement(label);
            }

            pill.setPosition(x, 0);
            row.addElement(pill);
            x += pillWidth + spacing;
        }
        return row;
    }
}
