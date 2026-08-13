package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** Shared colour palette and pin marker used by every waypoint view. */
final class WaypointPalette {
    static final String SHAPE_PIN = "pin";
    static final int[] COLORS = {
            0xF44336, 0xFF8A24, 0xFFD21F, 0x8DDB29,
            0x22C55E, 0x16C7B7, 0x25BDF2, 0x3282F6,
            0x6557E8, 0xA855F7, 0xEC4899, 0xF5F5F5
    };

    private WaypointPalette() {}

    static int colorForIndex(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    static int normalize(int color, int fallbackIndex) {
        int rgb = color & 0xFFFFFF;
        return rgb == 0 ? colorForIndex(fallbackIndex) : rgb;
    }

    static int next(int color) {
        int rgb = color & 0xFFFFFF;
        for (int index = 0; index < COLORS.length; index++) {
            if (COLORS[index] == rgb) return colorForIndex(index + 1);
        }
        return COLORS[0];
    }

    /** Legacy circle/head values are intentionally migrated to the only marker: pin. */
    static String normalizeShape(String shape) {
        return SHAPE_PIN;
    }

    static int readableTextColor(int color) {
        int rgb = color & 0xFFFFFF;
        int red = Math.min(255, ((rgb >> 16) & 0xFF) + 70);
        int green = Math.min(255, ((rgb >> 8) & 0xFF) + 70);
        int blue = Math.min(255, (rgb & 0xFF) + 70);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static void drawMarker(DrawContext context, int anchorX, int anchorY,
                           int requestedSize, int color, String shape) {
        drawSquare(context, anchorX, anchorY, requestedSize, color);
    }

    static boolean hitMarker(double mouseX, double mouseY, int anchorX, int anchorY,
                             int requestedSize, String shape) {
        int size = MathHelper.clamp(requestedSize, 2, 64);
        int half = size / 2;
        return mouseX >= anchorX - half - 3 && mouseX <= anchorX + half + 3
                && mouseY >= anchorY - size - 3 && mouseY <= anchorY + 3;
    }

    static double markerCenterY(int anchorY, int requestedSize, String shape) {
        int size = MathHelper.clamp(requestedSize, 2, 64);
        return anchorY - size * 0.5;
    }

    /** Draws a flat, unrotated, semi-transparent colour-filled square sitting on the
     *  anchor point with a plain "X" letter in the centre, and no border. The fill is
     *  translucent (not opaque) on purpose: the in-world HUD marker can land exactly
     *  on top of the crosshair, and translucency keeps the crosshair visible underneath
     *  instead of blocking it outright. */
    private static void drawSquare(DrawContext context, int anchorX, int anchorY,
                                   int requestedSize, int color) {
        int size = MathHelper.clamp(requestedSize, 2, 64);
        int left = anchorX - size / 2;
        int top = anchorY - size;
        int right = left + size;
        int bottom = anchorY;
        int argb = 0x70000000 | color & 0xFFFFFF;

        context.fill(left, top, right, bottom, argb);
        drawCenteredX(context, left + size / 2.0f, top + size / 2.0f, size);
    }

    // The map screen and the small minimap box can each show dozens of waypoints with
    // no cap on count, every frame. The glyph itself and its metrics never change once
    // the font is loaded, so build the Text once and cache its width/height instead of
    // reallocating and re-measuring them on every single marker drawn every frame.
    private static final Text X_MARK = Text.literal("X");
    private static int cachedGlyphWidth = -1;
    private static int cachedGlyphHeight = -1;

    /** Renders a real "X" glyph from the font (thin at any size, unlike a hand-drawn
     *  cross) scaled to sit inset within the marker square. */
    private static void drawCenteredX(DrawContext context, float centerX, float centerY, int size) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (cachedGlyphWidth < 0) {
            cachedGlyphWidth = Math.max(1, client.textRenderer.getWidth(X_MARK));
            cachedGlyphHeight = Math.max(1, client.textRenderer.fontHeight);
        }
        float scale = (size * 0.85f) / Math.max(cachedGlyphWidth, cachedGlyphHeight);
        float drawWidth = cachedGlyphWidth * scale;
        float drawHeight = cachedGlyphHeight * scale;

        // A single plain draw, no hand-rolled drop-shadow duplicate: the extra offset
        // copy used to make the glyph read as noticeably thicker along its lower edge.
        PlatformCompat.push(context);
        PlatformCompat.translate(context, centerX - drawWidth / 2f, centerY - drawHeight / 2f);
        PlatformCompat.scale(context, scale, scale);
        context.drawText(client.textRenderer, X_MARK, 0, 0, 0xFFFFFFFF, false);
        PlatformCompat.pop(context);
    }
}
