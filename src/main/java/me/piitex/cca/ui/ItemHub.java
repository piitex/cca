package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.model.HubItem;
import me.piitex.cca.model.HubLayout;
import me.piitex.cca.model.HubLayout.Entry;
import me.piitex.cca.model.HubLayout.Folder;
import me.piitex.cca.theme.Palette;
import me.piitex.engine.Window;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.containers.ReorderableGrid;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// The Characters and Users pages. A grid of cards and folder cards that can be dragged around.
// Dropping a card on a folder moves it in, dropping it on another card makes a new folder.
// What a card does when it's opened, edited, copied or deleted is up to the page, see CharacterHub and UserHub.
public abstract class ItemHub<T extends HubItem> {
    private final MainMenu owner;
    private final Window window = App.window;
    // Read again every time the hub is built, so a saved setting shows up on the next redraw.
    private CharacterCards.Look look = CharacterCards.Look.of(App.instance.getAppearance());

    private static final Color CARD_BORDER = CharacterCards.BORDER;

    private final HubLayout<T> layout;

    // The folder open in the popout over the hub, null when there isn't one.
    private Folder popoutFolder;
    private ModalContainer popout;
    private Container popoutPanel;
    // Where the user dragged the popout to, kept while it's rebuilt. Null centers it.
    private double[] popoutAt;
    private ReorderableGrid topGrid;

    protected ItemHub(MainMenu owner, List<T> loaded, HubLayout.Keys keys) {
        this.owner = owner;
        this.layout = new HubLayout<>(loaded, App.instance.getAppConfig().layoutStorage(), keys);
    }

    // "Character" or "User", for the buttons and messages.
    protected abstract String noun();

    // Clicking a card.
    protected abstract void open(T item);

    protected abstract void edit(T item);

    protected abstract void create();

    protected abstract boolean idTaken(String id);

    protected abstract T copy(T item, String newId) throws IOException;

    // Deletes it from disk. The hub takes it off the page.
    protected abstract void erase(T item) throws IOException;

    protected abstract String deleteWarning();

    protected abstract String emptyHint();

    public void add(T item) {
        layout.add(item);
    }

    // New (Character or User), New Folder and Sort A-Z from right to left. Returns the bottom of the buttons.
    double addHeaderButtons(Container content, double right, double y) {
        double buttonWidth = 172, buttonHeight = 42;
        ButtonOverlay newItem = Components.accentButton("+ New " + noun(), 14f, buttonWidth, buttonHeight);
        newItem.setPosition(right - buttonWidth, y);
        newItem.onAction(this::create);
        content.addElement(newItem);
        right -= buttonWidth + 12;

        right = addTextButton(content, "New Folder", 112, right, y + 3, () -> promptFolderName("New folder", "", "Create", name -> {
            layout.createFolder(name);
            owner.renderContent();
        }));

        if (!layout.topLevelIsByName()) {
            addTextButton(content, "Sort A-Z", 100, right, y + 3, () -> {
                layout.resetTopOrder();
                owner.renderContent();
            });
        }
        return y + buttonHeight;
    }

    private double addTextButton(Container content, String label, double width, double right, double y, Runnable action) {
        ButtonOverlay button = Components.textButton(label, width, 36);
        button.setPosition(right - width, y);
        button.onAction(action);
        content.addElement(button);
        return right - width - 12;
    }

    Element build(double width, double height) {
        look = CharacterCards.Look.of(App.instance.getAppearance());
        if (layout.isEmpty()) return buildEmptyState(width, height);

        ReorderableGrid grid = new ReorderableGrid(width, height, look.width(), look.height(), look.gap());
        topGrid = grid;

        Map<Container, Entry<T>> byCard = new HashMap<>();
        List<Container> cards = new ArrayList<>();
        for (Entry<T> entry : layout.topLevel()) {
            Container card = entry.isFolder() ? folderCard(entry.folder()) : card(entry.item());
            card.setContextMenu(entry.isFolder() ? folderMenu(entry.folder()) : itemMenu(entry.item()));
            byCard.put(card, entry);
            cards.add(card);
        }
        grid.setItems(cards);

        grid.onItemClick(card -> {
            Entry<T> entry = byCard.get(card);
            if (entry.isFolder()) {
                openPopout(entry.folder());
            } else {
                open(entry.item());
            }
        });
        grid.onLift((card, lifted) -> card.getStyling().setBorderColor(lifted ? Palette.accent() : CARD_BORDER));

        // Only cards can be dropped onto something, not folders.
        grid.setDropTargets((dragged, target) -> !byCard.get(dragged).isFolder() && dragged != target);
        grid.onTargetHighlight((card, on) -> {
            card.getStyling().setBorderColor(on ? Palette.accent() : CARD_BORDER);
            card.getStyling().setBorderThickness(on ? 2.5f : Components.theme().getBorderThickness());
        });
        grid.onDropOnto((dragged, target) -> {
            T item = byCard.get(dragged).item();
            Entry<T> onto = byCard.get(target);
            if (onto.isFolder()) {
                layout.move(item, onto.folder());
                owner.renderContent();
                return;
            }
            // Put the cards back while the name is asked for, nothing is grouped unless they confirm.
            owner.renderContent();
            promptFolderName("New folder", "New folder", "Create", name -> {
                layout.group(onto.item(), item, name);
                owner.renderContent();
            });
        });

        grid.onReorder(order -> {
            boolean wasCustom = !layout.topLevelIsByName();
            layout.setTopOrder(order.stream().map(byCard::get).toList());
            // "Sort A-Z" shows up once the order is custom, so redraw to show it.
            if (wasCustom != !layout.topLevelIsByName()) Scheduler.runLater(owner::renderContent);
        });
        return grid;
    }

    private Element buildEmptyState(double width, double height) {
        VerticalLayout box = new VerticalLayout(width, height);
        box.setAlignment(Layout.Alignment.CENTER);
        box.setSpacing(14f);
        Components.clearStyle(box);

        box.addElement(Components.gradientBadge(64, Feather.USERS, 28));
        box.addElement(new TextOverlay("No " + noun().toLowerCase() + "s yet", 16f, Components.theme().getText()));
        box.addElement(new TextOverlay(emptyHint(), 13f, Components.theme().getPlaceholderText()));

        ButtonOverlay create = Components.accentButton("Create " + noun(), 15f, 200, 46);
        create.onAction(this::create);
        box.addElement(create);
        return box;
    }

    // Folder popout

    // Called after every redraw. Rebuilds the popout over the hub so it shows any changes, or closes it
    // if the hub isn't showing anymore.
    void updatePopout(boolean hubVisible) {
        if (popoutFolder != null && hubVisible && layout.folders().contains(popoutFolder)) {
            showPopout(false);
        } else {
            closePopout();
        }
    }

    private void openPopout(Folder folder) {
        popoutFolder = folder;
        showPopout(true);
    }

    private void closePopout() {
        popoutFolder = null;
        popoutAt = null;
        ModalContainer old = popout;
        popout = null;
        if (old != null) old.close();
    }

    // A folder's cards in a see through panel over the hub, not the sidebar. Dragging a card out of the panel and
    // letting go over the hub takes it out of the folder. Clicking outside, Done or Escape closes it.
    private void showPopout(boolean animate) {
        Folder folder = popoutFolder;
        ModalContainer old = popout;
        popout = null; // So closing the old one isn't taken as the user closing the popout.
        if (old != null) {
            popoutAt = new double[]{popoutPanel.getX(), popoutPanel.getY()};
            old.close();
        }

        List<T> members = layout.members(folder);
        double pad = 24, headerHeight = 50;
        // The popout sits over the content, the sidebar stays usable.
        double[] area = owner.contentBounds();
        double windowWidth = area[2], windowHeight = area[3];

        int maxColumns = (int) Math.max(2, Math.min(App.instance.getAppearance().popoutColumns.get(), (windowWidth - 2 * pad - 96 + look.gap()) / (look.width() + look.gap())));
        int columns = Math.max(2, Math.min(maxColumns, members.size()));
        double gridWidth = columns * look.width() + (columns - 1) * look.gap();
        int rows = Math.max(1, (members.size() + columns - 1) / columns);
        double fullHeight = rows * (look.height() + look.gap()) - look.gap();
        double gridHeight = members.isEmpty() ? 150 : Math.min(fullHeight, Math.max(look.height(), windowHeight - 2 * pad - headerHeight - 96));

        double panelWidth = gridWidth + 2 * pad;
        Container panel = new Container(panelWidth, pad + headerHeight + gridHeight + pad);
        Components.fill(panel, Palette.panel(App.instance.getAppearance().popoutOpacity.get().floatValue()));
        panel.getStyling().setBorderColor(Components.theme().getContainerBorder());
        panel.getStyling().setBorderThickness(1f);
        panel.getStyling().setCornerRadius(20f);
        // Can be dragged by anything that isn't a card or a button.
        panel.setDraggable(true);
        popoutPanel = panel;

        ModalContainer modal = new ModalContainer(panel);
        modal.setBackdropColor(new Color(0f, 0f, 0f, 0.22f));
        if (!animate) modal.setFadeSeconds(0f);
        modal.onClose(() -> {
            if (popout == modal) {
                popout = null;
                popoutFolder = null;
            }
        });

        boolean sorted = layout.membersAreByName(folder);
        double buttonsWidth = 80 + 8 + (sorted ? 0 : 100 + 8);
        TextOverlay title = Components.fitText(folder.getName(), 20f, Components.theme().getText(), panelWidth - 2 * pad - buttonsWidth - 12);
        title.setPosition(pad, pad);
        panel.addElement(title);

        ButtonOverlay done = Components.accentButton("Done", 13f, 80, 34);
        done.setPosition(panelWidth - pad - 80, pad - 2);
        done.onAction(this::closePopout);
        panel.addElement(done);

        if (!sorted) {
            ButtonOverlay sort = Components.textButton("Sort A-Z", 100, 34);
            sort.setPosition(panelWidth - pad - 80 - 8 - 100, pad - 2);
            sort.onAction(() -> {
                layout.resetMembersOrder(folder);
                owner.renderContent();
            });
            panel.addElement(sort);
        }

        if (members.isEmpty()) {
            TextOverlay empty = new TextOverlay("This folder is empty", 16f, Components.theme().getText());
            empty.setPosition((panelWidth - empty.getWidth()) / 2.0, pad + headerHeight + (gridHeight - empty.getHeight()) / 2.0);
            panel.addElement(empty);
        } else {
            ReorderableGrid grid = buildPopoutGrid(modal, panel, folder, members, gridWidth, gridHeight);
            grid.setPosition(pad, pad + headerHeight);
            panel.addElement(grid);
        }

        popout = modal;
        modal.setArea(area[0], area[1], area[2], area[3]);
        modal.show(window);
        if (popoutAt != null) panel.moveTo(popoutAt[0], popoutAt[1]);
    }

    private ReorderableGrid buildPopoutGrid(ModalContainer modal, Container panel, Folder folder, List<T> members, double gridWidth, double gridHeight) {
        ReorderableGrid grid = new ReorderableGrid(gridWidth, gridHeight, look.width(), look.height(), look.gap());
        // Leaving the panel takes a card out, not leaving the grid. The header is part of the popout.
        grid.setDropBounds(panel);

        Map<Container, T> byCard = new HashMap<>();
        List<Container> cards = new ArrayList<>();
        for (T item : members) {
            Container card = card(item);
            card.setContextMenu(itemMenu(item));
            byCard.put(card, item);
            cards.add(card);
        }
        grid.setItems(cards);
        grid.onItemClick(card -> {
            T item = byCard.get(card);
            closePopout();
            open(item);
        });

        // The grid clips cards at its edge, so once the pointer leaves the panel the card is swapped for
        // a copy on the modal that follows the pointer over the hub.
        Container[] held = new Container[1];
        Container[] ghost = new Container[1];
        double[] center = new double[2];
        grid.onLift((card, lifted) -> {
            held[0] = lifted ? card : null;
            card.getStyling().setBorderColor(lifted ? Palette.accent() : CARD_BORDER);
        });
        grid.onFollow((card, x, y) -> {
            center[0] = x + look.width() / 2;
            center[1] = y + look.height() / 2;
            if (ghost[0] != null) ghost[0].setPosition(x, y);
        });
        grid.onOutsideChanged(out -> {
            Container card = held[0];
            if (card == null) return;
            if (out) {
                ghost[0] = card(byCard.get(card));
                ghost[0].getStyling().setBorderColor(Palette.accent());
                ghost[0].setPosition(center[0] - look.width() / 2, center[1] - look.height() / 2);
                modal.addElement(ghost[0]);
                card.setEnabled(false);
            } else {
                if (ghost[0] != null) modal.removeElement(ghost[0]);
                ghost[0] = null;
                card.setEnabled(true);
            }
        });
        grid.onDropOutside(card -> moveOutOfFolder(byCard.get(card), center[0], center[1]));

        grid.onReorder(order -> {
            boolean wasCustom = !layout.membersAreByName(folder);
            layout.setMembersOrder(folder, order.stream().map(byCard::get).toList());
            if (wasCustom != !layout.membersAreByName(folder)) Scheduler.runLater(owner::renderContent);
        });
        return grid;
    }

    // Dropped over the hub grid it goes in that slot, anywhere else it goes next to its folder.
    private void moveOutOfFolder(T item, double x, double y) {
        int slot = topGrid != null && topGrid.contains(x, y) ? topGrid.slotIndexAt(x, y) : -1;
        layout.move(item, null);
        if (slot >= 0) {
            List<Entry<T>> order = new ArrayList<>(layout.topLevel());
            Entry<T> mine = order.stream().filter(e -> !e.isFolder() && e.item() == item).findFirst().orElse(null);
            if (mine != null) {
                order.remove(mine);
                order.add(Math.min(slot, order.size()), mine);
                layout.setTopOrder(order);
            }
        }
        owner.renderContent();
    }

    // Right click menus

    private PopupMenu itemMenu(T item) {
        Folder current = layout.folderOf(item);

        List<MenuItem> move = new ArrayList<>();
        for (Folder folder : layout.folders()) {
            MenuItem entry = new MenuItem(folder.getName(), () -> {
                layout.move(item, folder);
                owner.renderContent();
            });
            entry.setEnabled(folder != current);
            move.add(entry);
        }
        if (!move.isEmpty()) move.add(MenuItem.separator());
        move.add(new MenuItem("New folder...", () -> promptFolderName("New folder", "", "Create", name -> {
            layout.move(item, layout.createFolder(name));
            owner.renderContent();
        })));

        PopupMenu menu = new PopupMenu();
        menu.add(MenuItem.submenu("Move to folder", move.toArray(new MenuItem[0])));
        if (current != null) {
            menu.add("Remove from folder", () -> {
                layout.move(item, null);
                owner.renderContent();
            });
        }
        menu.addSeparator();
        menu.add("Edit", () -> edit(item));
        menu.add("Duplicate", () -> duplicate(item));
        menu.add("Delete", () -> confirmDelete(item));
        return menu;
    }

    private PopupMenu folderMenu(Folder folder) {
        PopupMenu menu = new PopupMenu();
        menu.add("Open", () -> openPopout(folder));
        menu.add("Rename...", () -> renameFolder(folder));
        menu.addSeparator();
        menu.add("Delete folder", () -> deleteFolder(folder));
        return menu;
    }

    private void renameFolder(Folder folder) {
        promptFolderName("Rename folder", folder.getName(), "Rename", name -> {
            layout.renameFolder(folder, name);
            owner.renderContent();
        });
    }

    // Only removes the folder, the cards go back to the top level.
    private void deleteFolder(Folder folder) {
        layout.deleteFolder(folder);
        owner.renderContent();
    }

    private void promptFolderName(String title, String initial, String confirmLabel, Consumer<String> onConfirm) {
        Folder open = suspendPopout();
        Components.promptText(title, "Folder name", initial, confirmLabel, 40, false, name -> {
            popoutFolder = open;
            onConfirm.accept(name);
        }, () -> Scheduler.runLater(() -> reopenPopout(open)));
    }

    // Copies to <id>-copy (or -copy-2 and so on) and puts it right next to the original.
    private void duplicate(T item) {
        String baseId = item.getId() + "-copy";
        String newId = baseId;
        int suffix = 2;
        while (idTaken(newId)) {
            newId = baseId + "-" + suffix++;
        }
        try {
            layout.addBeside(item, copy(item, newId));
            App.logger.info("Duplicated {} '{}' to '{}'", noun().toLowerCase(), item.getId(), newId);
            owner.renderContent();
        } catch (IOException e) {
            App.logger.error("Could not duplicate {} '{}'", noun().toLowerCase(), item.getId(), e);
        }
    }

    // Deleting can't be undone, so always ask first.
    private void confirmDelete(T item) {
        Folder open = suspendPopout();
        Components.confirmDelete("Delete " + item.getDisplayName() + "?", deleteWarning(),
                () -> delete(item, open), () -> Scheduler.runLater(() -> reopenPopout(open)));
    }

    private void delete(T item, Folder open) {
        try {
            erase(item);
            layout.remove(item);
        } catch (IOException e) {
            App.logger.error("Could not delete {} '{}'", noun().toLowerCase(), item.getId(), e);
        }
        // The redraw brings the popout back with the card gone.
        popoutFolder = open;
        owner.renderContent();
    }

    // Only one dialog can be open, so showing one closes the folder popout. This remembers the folder and where
    // it was dragged to. Confirming sets popoutFolder back before redrawing, canceling calls reopenPopout.
    private Folder suspendPopout() {
        Folder open = popoutFolder;
        if (open != null) popoutAt = new double[]{popoutPanel.getX(), popoutPanel.getY()};
        return open;
    }

    private void reopenPopout(Folder folder) {
        if (folder == null || popoutFolder != null || !layout.folders().contains(folder)) return;
        popoutFolder = folder;
        showPopout(false);
    }

    private Container card(T item) {
        return CharacterCards.character(look, CharacterCards.Face.of(item),
                () -> edit(item), () -> duplicate(item), () -> confirmDelete(item));
    }

    private Container folderCard(Folder folder) {
        return CharacterCards.folder(look, folder.getName(), layout.members(folder).stream().map(CharacterCards.Face::of).toList(),
                () -> renameFolder(folder), () -> deleteFolder(folder));
    }
}
