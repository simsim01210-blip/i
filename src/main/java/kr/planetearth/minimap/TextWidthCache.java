package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.Map;

/** Pixel widths of the label strings drawn every frame (marker/territory names,
 *  player names, the status bar). TextRenderer#getWidth walks every glyph through
 *  the font storage each call, and with dense Korean town names on a zoomed-out map
 *  that was hundreds of measurements per frame for strings that never change.
 *  Render thread only. Flushed periodically so a resource-pack font change is
 *  picked up within a few seconds, and capped so it can't grow without bound. */
final class TextWidthCache {
    private static final int MAX_ENTRIES = 4096;
    private static final long FLUSH_INTERVAL_MILLIS = 30_000L;
    private static final Map<String, Integer> WIDTHS = new HashMap<>();
    private static long nextFlushMillis;

    private TextWidthCache() {}

    static int width(String text) {
        if (text == null || text.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        if (now >= nextFlushMillis || WIDTHS.size() >= MAX_ENTRIES) {
            WIDTHS.clear();
            nextFlushMillis = now + FLUSH_INTERVAL_MILLIS;
        }
        Integer cached = WIDTHS.get(text);
        if (cached != null) return cached;
        int measured = MinecraftClient.getInstance().textRenderer.getWidth(text);
        WIDTHS.put(text, measured);
        return measured;
    }
}
