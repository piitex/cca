package me.piitex.cca.model;

// What the hub needs of a character or a user to put a card on the page.
public interface HubItem {
    String getId();

    String getDisplayName();

    String getIconPath();
}
