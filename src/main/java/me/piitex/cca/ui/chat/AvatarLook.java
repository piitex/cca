package me.piitex.cca.ui.chat;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.config.ImageOptions;

// The avatar settings, read once when a style is made.
record AvatarLook(boolean show, boolean showUser, AvatarShape shape, double width, double height, double portraitWidth, double portraitHeight, ImageOptions image) {

    static AvatarLook of(AppearanceSettings s) {
        return new AvatarLook(s.avatarShow.get(), s.avatarShowUser.get(), AvatarShape.fromId(s.avatarShape.get()),
                s.avatarWidth.get(), s.avatarHeight.get(), s.portraitWidth.get(), s.portraitHeight.get(), s.avatarImage);
    }

    boolean shownFor(boolean fromUser) {
        return show && (!fromUser || showUser);
    }
}
