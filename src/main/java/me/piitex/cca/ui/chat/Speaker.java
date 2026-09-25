package me.piitex.cca.ui.chat;

import me.piitex.cca.model.Character;

// What a style needs to know about who is talking, so the settings preview can use a made up one.
record Speaker(String id, String name, String iconPath) {

    static Speaker of(Character character) {
        return new Speaker(character.getId(), character.getDisplayName(), character.getIconPath());
    }

    // Null stays null, the user side falls back to "You" and the app's badge.
    static Speaker userOf(Character character) {
        return character.hasUser() ? new Speaker("user:" + character.getId(), character.getUserName(), character.getUserIconPath()) : null;
    }
}
