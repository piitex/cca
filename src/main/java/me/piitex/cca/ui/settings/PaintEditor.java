package me.piitex.cca.ui.settings;

import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.AppTheme.Slot;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.SPINNER_WIDTH;

// The rows for one theme color. A kind dropdown (a color, a gradient, a gradient that moves or a rainbow) and the
// controls that kind needs. Edits write straight to the theme and restyle in place, picking another kind or
// the number of colors changes which rows exist so those redraw the screen.
final class PaintEditor {
    enum Kind {
        SOLID("Color"),
        GRADIENT("Gradient"),
        MOVING("Moving gradient"),
        RAINBOW("Rainbow");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        static Kind of(Paint paint) {
            Paint p = AnimatedPaint.resolve(paint);
            if (p instanceof RainbowColor) return RAINBOW;
            if (p instanceof ScrollingGradient) return MOVING;
            if (p instanceof LinearGradient) return GRADIENT;
            return SOLID;
        }
    }

    private static final double KIND_WIDTH = 150;
    private static final double SWATCH_WIDTH = 150;
    private static final float DEFAULT_SPEED = 40f;
    private static final float DEFAULT_SECONDS = 6f;

    private final AppTheme theme;
    private final Slot slot;
    private final Runnable edited;
    private final Runnable redraw;
    // The strip beside the kind dropdown that shows a gradient or rainbow as it moves.
    private Container swatch;

    private PaintEditor(AppTheme theme, Slot slot, Runnable edited, Runnable redraw) {
        this.theme = theme;
        this.slot = slot;
        this.edited = edited;
        this.redraw = redraw;
    }

    /**
     * Adds the rows for a theme color to the open card of the form.
     *
     * @param edited called after every change so the preview can restyle
     * @param redraw rebuilds the screen, for when the rows change
     */
    static void rows(SettingsForm f, AppTheme theme, Slot slot, String help, Runnable edited, Runnable redraw) {
        new PaintEditor(theme, slot, edited, redraw).build(f, help);
    }

    private void build(SettingsForm f, String help) {
        Paint paint = theme.get(slot);
        Kind kind = Kind.of(paint);

        Container control = Components.transparent(slot.allowsPaint() ? KIND_WIDTH + 8 + SWATCH_WIDTH : SWATCH_WIDTH, CONTROL_HEIGHT);
        if (slot.allowsPaint()) {
            DropdownOverlay<Kind> kinds = new DropdownOverlay<>(KIND_WIDTH, CONTROL_HEIGHT);
            kinds.setFontSize(14f);
            kinds.setLabelProvider(k -> k.label);
            kinds.setItems(List.of(Kind.values()));
            kinds.setSelected(kind);
            kinds.onSelect(this::convert);
            control.addElement(kinds);
        }
        double x = slot.allowsPaint() ? KIND_WIDTH + 8 : 0;
        if (kind == Kind.SOLID) {
            ColorPickerOverlay picker = new ColorPickerOverlay(SWATCH_WIDTH, CONTROL_HEIGHT, theme.solid(slot));
            // The window can't be see through, there's nothing behind it.
            picker.setAlphaEnabled(slot != Slot.WINDOW);
            picker.onChange(color -> apply(color));
            picker.setPosition(x, 0);
            control.addElement(picker);
        } else {
            swatch = new Container(SWATCH_WIDTH, CONTROL_HEIGHT);
            swatch.getStyling().setBorderThickness(1f);
            swatch.getStyling().setBorderColor(new Color(0.5f, 0.5f, 0.5f, 0.55f));
            swatch.getStyling().setCornerRadius(8f);
            swatch.setPosition(x, 0);
            control.addElement(swatch);
            restyleSwatch();
        }
        f.row(slot.label(), help, control);

        switch (kind) {
            case GRADIENT, MOVING -> gradientRows(f, (LinearGradient) AnimatedPaint.resolve(paint), kind == Kind.MOVING);
            case RAINBOW -> rainbowRows(f, (RainbowColor) AnimatedPaint.resolve(paint));
            default -> {
            }
        }
    }

    private void gradientRows(SettingsForm f, LinearGradient gradient, boolean moving) {
        SpinnerOverlay count = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, 2, AppTheme.MAX_STOPS, gradient.getColors().size());
        count.setStep(1);
        count.setValue(gradient.getColors().size());
        count.onValueChange(v -> {
            int wanted = (int) Math.round(v);
            if (wanted == gradient().getColors().size()) return;
            apply(withStopCount(gradient(), wanted));
            redraw.run();
        });
        f.row("    Colors", "How many colors the gradient passes through, spread evenly.", count);

        for (int i = 0; i < gradient.getColors().size(); i++) {
            int index = i;
            ColorPickerOverlay picker = new ColorPickerOverlay(SWATCH_WIDTH, CONTROL_HEIGHT, gradient.getColors().get(i));
            picker.setAlphaEnabled(slot != Slot.WINDOW);
            picker.onChange(color -> apply(withStop(gradient(), index, color)));
            f.row("    Color " + (i + 1), null, picker);
        }

        SpinnerOverlay angle = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, 0, 360, gradient.hasAngle() ? gradient.getAngle() : 45);
        angle.setStep(15);
        angle.setDecimals(0);
        angle.setValue(gradient.hasAngle() ? gradient.getAngle() : 45);
        angle.onValueChange(v -> apply(withAngle(gradient(), v.floatValue())));
        f.row("    Direction", "In degrees. 0 runs left to right, 90 top to bottom.", angle);

        if (!moving) return;
        float speed = gradient instanceof ScrollingGradient scrolling ? scrolling.getSpeed() : DEFAULT_SPEED;
        SpinnerOverlay speedSpinner = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, -AppTheme.MAX_SPEED, AppTheme.MAX_SPEED, speed);
        speedSpinner.setStep(10);
        speedSpinner.setDecimals(0);
        speedSpinner.setValue(speed);
        speedSpinner.onValueChange(v -> apply(withSpeed(gradient(), v.floatValue())));
        f.row("    Speed", "How far it moves in a second. Negative goes the other way.", speedSpinner);
    }

    private void rainbowRows(SettingsForm f, RainbowColor rainbow) {
        SpinnerOverlay seconds = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, 1, 60, Math.abs(rainbow.getCycleDuration()));
        seconds.setStep(1);
        seconds.setDecimals(0);
        seconds.setValue(Math.max(1, Math.abs(rainbow.getCycleDuration())));
        seconds.onValueChange(v -> apply(withRainbow(rainbow(), v.floatValue(), null, null, null)));
        f.row("    Seconds per cycle", "How long it takes to go all the way round the colors.", seconds);

        f.row("    Saturation", "Low is pale, high is vivid.", unit(rainbow.getSaturation(), v -> withRainbow(rainbow(), null, v, null, null)));
        f.row("    Brightness", null, unit(rainbow.getBrightness(), v -> withRainbow(rainbow(), null, null, v, null)));
        if (slot != Slot.WINDOW) {
            f.row("    Opacity", null, unit(rainbow.getAlpha(), v -> withRainbow(rainbow(), null, null, null, v)));
        }
    }

    private SpinnerOverlay unit(float value, java.util.function.Function<Float, Paint> change) {
        SpinnerOverlay spinner = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, 0, 1, value);
        spinner.setStep(0.05);
        spinner.setDecimals(2);
        spinner.setValue(value);
        spinner.onValueChange(v -> apply(change.apply(v.floatValue())));
        return spinner;
    }

    // What's stored right now, read again for every edit so two edits in a row build on each other.
    private LinearGradient gradient() {
        return (LinearGradient) AnimatedPaint.resolve(theme.get(slot));
    }

    private RainbowColor rainbow() {
        return (RainbowColor) AnimatedPaint.resolve(theme.get(slot));
    }

    private void apply(Paint paint) {
        theme.set(slot, paint);
        restyleSwatch();
        edited.run();
    }

    private void restyleSwatch() {
        if (swatch != null) Components.fill(swatch, theme.fresh(slot));
    }

    // Picking another kind keeps what it can of the current paint.
    private void convert(Kind kind) {
        Paint current = theme.get(slot);
        if (kind == Kind.of(current)) return;
        Color base = theme.solid(slot);
        Paint converted = switch (kind) {
            case SOLID -> base;
            case GRADIENT, MOVING -> {
                List<Color> stops = current instanceof LinearGradient g ? g.getColors() : List.of(base, partner(base));
                float angle = current instanceof LinearGradient g && g.hasAngle() ? g.getAngle() : 0f;
                float speed = current instanceof ScrollingGradient s ? s.getSpeed() : DEFAULT_SPEED;
                yield kind == Kind.MOVING ? new ScrollingGradient(stops, angle, speed) : new LinearGradient(stops, angle);
            }
            case RAINBOW -> new RainbowColor(DEFAULT_SECONDS, 0.75f, 1f, base.getA());
        };
        apply(converted);
        redraw.run();
    }

    // A second color for a new gradient: the accent's own partner for the accent, otherwise a lighter or darker take.
    private Color partner(Color base) {
        if (slot == Slot.ACCENT) return theme.accentEnd();
        boolean light = (base.getR() + base.getG() + base.getB()) / 3f > 0.5f;
        float toward = light ? 0f : 1f;
        return new Color(mix(base.getR(), toward), mix(base.getG(), toward), mix(base.getB(), toward), base.getA());
    }

    private static float mix(float from, float to) {
        return from + (to - from) * 0.35f;
    }

    private static Paint withStop(LinearGradient gradient, int index, Color color) {
        return rebuild(gradient, stops -> {
            stops.set(index, color);
            return stops;
        });
    }

    private static Paint withStopCount(LinearGradient gradient, int count) {
        return rebuild(gradient, stops -> {
            while (stops.size() > count) stops.removeLast();
            while (stops.size() < count) stops.add(stops.getLast());
            return stops;
        });
    }

    private static Paint rebuild(LinearGradient gradient, UnaryOperator<List<Color>> change) {
        List<Color> stops = change.apply(new ArrayList<>(gradient.getColors()));
        return gradient instanceof ScrollingGradient s
                ? new ScrollingGradient(stops, s.getAngle(), s.getSpeed())
                : new LinearGradient(stops, gradient.getAngle());
    }

    private static Paint withAngle(LinearGradient gradient, float angle) {
        return gradient instanceof ScrollingGradient s
                ? new ScrollingGradient(s.getColors(), angle, s.getSpeed())
                : new LinearGradient(gradient.getColors(), angle);
    }

    private static Paint withSpeed(LinearGradient gradient, float speed) {
        return new ScrollingGradient(gradient.getColors(), gradient.getAngle(), speed);
    }

    // Null keeps what the rainbow has.
    private static Paint withRainbow(RainbowColor from, Float seconds, Float saturation, Float brightness, Float alpha) {
        RainbowColor copy = new RainbowColor(
                seconds != null ? seconds : from.getCycleDuration(),
                saturation != null ? saturation : from.getSaturation(),
                brightness != null ? brightness : from.getBrightness(),
                alpha != null ? alpha : from.getAlpha());
        copy.setHueOffset(from.getHueOffset());
        return copy;
    }
}
