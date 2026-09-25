package me.piitex.cca.ui;

import me.piitex.engine.ui.Element;

// A screen shown inside MainMenu's content area (chat, creators, models, settings).
// render is called again on every redraw (resize, divider drag, changes) so it has to build fresh elements each time.
public interface ContentMenu {

    String getTitle();

    Element render(double width, double height);

    // Called when the menu is left, so background work doesn't redraw a screen that's gone.
    default void close() {
    }
}
