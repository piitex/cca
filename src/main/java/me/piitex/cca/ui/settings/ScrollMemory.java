package me.piitex.cca.ui.settings;

import me.piitex.engine.ui.scroll.ScrollContainer;

// Settings screens are rebuilt from scratch a lot (toggles, resizing) and a new scroll pane starts at the top.
// Call capture() before rebuilding and the next form is put back where it was. reset() when switching tabs.
final class ScrollMemory {
    private ScrollContainer live;
    private double offset;

    void capture() {
        if (live != null) offset = live.getScrollOffsetY();
    }

    void reset() {
        live = null;
        offset = 0;
    }

    // scrollTo clamps, so a form that got shorter just ends at the bottom.
    void attach(ScrollContainer scroll) {
        live = scroll;
        scroll.scrollTo(0, offset);
    }
}
