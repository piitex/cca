package me.piitex.cca.ui.chat;

import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.RichTextAreaOverlay;
import me.piitex.engine.ui.text.TextSpanStyler;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;

/**
 * The text of a message. It's a read only text area sized to fit its text, so the chat list scrolls
 * instead of the message and the text can still be selected and copied.
 * <p>
 * The editor is the same thing with editing turned on, so nothing moves when a message is edited.
 * Double clicking the read only text reports where it was clicked so the caret can start there.
 */
final class MessageBody extends RichTextAreaOverlay {

    // styler can be null
    record Look(float fontSize, Color textColor, float lineSpacing, TextSpanStyler styler) {
    }

    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DOUBLE_CLICK_MAX_DISTANCE = 6.0;

    private IntConsumer onDoubleClick;
    private Runnable onCancel;
    private long lastPressNanos = Long.MIN_VALUE;
    private double lastPressX, lastPressY;

    MessageBody(Look look, String content, boolean editable) {
        super(0, 0, 10, 0, look.fontSize(), look.textColor());
        setText(content);
        setPaddingX(0f);
        setPaddingY(0f);
        setLineSpacing(look.lineSpacing());
        setScrollbarVisible(false);
        if (look.styler() != null) setStyler(look.styler());
        getStyling().setBackgroundColor(Color.TRANSPARENT);
        getStyling().setHoverColor(Color.TRANSPARENT);
        getStyling().setBorderThickness(0f);
        setReadOnly(!editable);
        setCaretColor(editable ? look.textColor() : Color.TRANSPARENT);
    }

    // Sizes the height to exactly the wrapped text. With 0 height and no padding the scroll range is
    // the text's height, so it matches how the text area measures itself.
    void fit(double width) {
        setWidth(width);
        setHeight(0);
        setHeight(getMaxScrollY());
    }

    // Gets the character index that was double clicked, only while read only.
    void onDoubleClick(IntConsumer handler) {
        this.onDoubleClick = handler;
    }

    // Escape in the editor.
    void onCancel(Runnable handler) {
        this.onCancel = handler;
    }

    // The base class only moves the caret through an edit, so replace nothing at that spot and drop the undo it made.
    void placeCaret(int index) {
        replaceRange(index, index, "");
        getUndoHistory().clear();
    }

    @Override
    public void onDragStart(double x, double y) {
        super.onDragStart(x, y); // Still lets the base class select the word on double click.
        long now = System.nanoTime();
        boolean repeat = now - lastPressNanos <= DOUBLE_CLICK_NANOS && Math.hypot(x - lastPressX, y - lastPressY) <= DOUBLE_CLICK_MAX_DISTANCE;
        lastPressNanos = repeat ? Long.MIN_VALUE : now; // A third click starts over.
        lastPressX = x;
        lastPressY = y;
        if (repeat && isReadOnly() && onDoubleClick != null) {
            int index = indexAtPoint(x, y);
            IntConsumer handler = onDoubleClick;
            // Starting an edit rebuilds the list this is in, so wait for the next frame.
            Scheduler.runLater(() -> handler.accept(index));
        }
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && !isReadOnly() && onCancel != null) {
            if (action == GLFW.GLFW_PRESS) onCancel.run();
            return;
        }
        super.onKeyPressed(key, action, mods);
    }

    // It's always sized to its text so it never scrolls. Returning false lets the chat list scroll
    // when the mouse is over a long message.
    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        return false;
    }
}
