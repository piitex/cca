package me.piitex.cca.ui.settings;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.ui.CharacterCards;
import me.piitex.cca.ui.CharacterCards.Face;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.scroll.ScrollContainer;

import java.util.List;

// The hub's real cards with made up characters, for Settings > Characters. A column beside the options with a
// character and a folder stacked in it, scrolling when the window is too short for both. show() lays them out again on every edit.
final class CardPreview {
    // Wide enough for the widest card the settings allow.
    static final double WIDTH = 272;

    private static final double PADDING = 16;
    private static final List<Face> FACES = List.of(new Face("Aria", "Aria", ""), new Face("Kai", "Kai", ""),
            new Face("Mira", "Mira", ""), new Face("Sol", "Sol", ""), new Face("Lune", "Lune", ""));

    private final Container root;
    private final ScrollContainer list;

    CardPreview(double height) {
        root = Components.panel(WIDTH, height);
        root.getStyling().setBorderThickness(1f);
        root.getStyling().setBorderColor(Components.theme().getContainerBorder());
        list = new ScrollContainer(WIDTH - 2 * PADDING, Math.max(0, height - 2 * PADDING));
        list.setPosition(PADDING, PADDING);
        root.addElement(list);
    }

    Element element() {
        return root;
    }

    void show(AppearanceSettings settings) {
        double offset = list.getScrollOffsetY();
        Components.clear(list);
        CharacterCards.Look look = CharacterCards.Look.of(settings);
        Runnable none = () -> {
        };

        List<Container> cards = List.of(
                CharacterCards.character(look, FACES.get(0), none, none, none),
                CharacterCards.folder(look, "Friends", FACES, none, none));
        double gap = Math.min(PADDING, look.gap());
        double x = (list.getWidth() - look.width()) / 2.0, y = 0;
        for (Container card : cards) {
            card.setPosition(x, y);
            list.addElement(card);
            y += look.height() + gap;
        }
        list.scrollTo(0, Math.min(offset, list.getMaxScrollY()));
    }
}
