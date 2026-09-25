package me.piitex.cca.ui.chat;

import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.utils.Platform;

// The right click menu on a message. Same actions as the button row so the two never get out of sync.
final class MessageActionMenu {

    private MessageActionMenu() {
    }

    // Null when there's nothing to offer, like a reply that's still generating.
    static PopupMenu build(MessageActions actions) {
        PopupMenu menu = new PopupMenu();

        if (actions.editing() != null) {
            MenuItem save = new MenuItem("Save", actions.editing().save());
            save.setShortcut(Platform.getCurrent() == Platform.MACOS ? "Cmd+Enter" : "Ctrl+Enter");
            menu.add(save);
            MenuItem cancel = new MenuItem("Cancel", actions.editing().cancel());
            cancel.setShortcut("Esc");
            menu.add(cancel);
            return menu;
        }

        MessageActions.Variants variants = actions.shownVariants();
        if (variants != null) {
            MenuItem previous = new MenuItem("Previous response", variants.previous());
            previous.setShortcut(ChatShortcuts.PREVIOUS.label());
            previous.setEnabled(variants.index() > 0);
            menu.add(previous);
            MenuItem next = new MenuItem("Next response", variants.next());
            next.setShortcut(ChatShortcuts.NEXT.label());
            next.setEnabled(variants.index() < variants.count() - 1);
            menu.add(next);
            menu.addSeparator();
        }

        if (actions.copy() != null) menu.add("Copy", actions.copy());
        if (actions.edit() != null) menu.add(item("Edit", () -> actions.edit().accept(-1), actions.newest() ? ChatShortcuts.EDIT : null));
        if (actions.regenerate() != null) menu.add(item("Regenerate", actions.regenerate(), ChatShortcuts.REGENERATE));
        if (actions.delete() != null) {
            menu.addSeparator();
            menu.add(item("Delete", actions.delete(), actions.newest() ? ChatShortcuts.DELETE : null));
        }

        return menu.getItems().isEmpty() ? null : menu;
    }

    private static MenuItem item(String text, Runnable action, ChatShortcuts shortcut) {
        MenuItem item = new MenuItem(text, action);
        if (shortcut != null) item.setShortcut(shortcut.label());
        return item;
    }
}
