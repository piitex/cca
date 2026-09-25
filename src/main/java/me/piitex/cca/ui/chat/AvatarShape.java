package me.piitex.cca.ui.chat;

// How avatars are cut, by the id saved as chat.avatar.shape.
public enum AvatarShape {
    CIRCLE("circle", "Circle"),
    ROUNDED("rounded", "Rounded square"),
    SQUARE("square", "Square");

    private final String id;
    private final String label;

    AvatarShape(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    float radius(double width, double height) {
        double shortSide = Math.min(width, height);
        return switch (this) {
            case CIRCLE -> (float) (shortSide / 2);
            case ROUNDED -> (float) Math.clamp(shortSide * 0.2, 6, 16);
            case SQUARE -> 0f;
        };
    }

    // Anything unknown is a circle.
    public static AvatarShape fromId(String id) {
        for (AvatarShape shape : values()) {
            if (shape.id.equalsIgnoreCase(id)) return shape;
        }
        return CIRCLE;
    }
}
