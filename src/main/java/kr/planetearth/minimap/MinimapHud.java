package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MinimapHud {
    private static final Identifier LOADING_HEAD = Identifier.tryParse(
            PlanetEarthMinimapClient.MOD_ID + ":textures/gui/loading_head.png");
    // The small corner minimap and the "hold G" overlay map can both be on screen in
    // the same frame and cover different areas, so each needs its own independent
    // loading-indicator state. Sharing one used to make the indicator flicker: whichever
    // of the two rendered last each frame would stomp on the other's show/hide timer.
    private static final LoadingState MINIMAP_LOADING = new LoadingState();
    private static final LoadingState OVERLAY_LOADING = new LoadingState();
    private static boolean overlayMapWasHeld;
    // Reused every frame instead of allocating a fresh ArrayList to sort-by-distance
    // in, since this runs unconditionally whenever any waypoints are shown at all.
    private static final List<MinimapConfig.Waypoint> waypointDistanceScratch = new ArrayList<>();

    private MinimapHud() {}

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;
        // The full map screen already draws its own opaque, full-window background and
        // its own waypoint markers, so leaving this HUD layer running underneath it was
        // pointless work that could show through as ghosted waypoint text around its
        // edges — same reasoning as already applied to the editor screen below.
        if (client.currentScreen instanceof MinimapEditorScreen
                || client.currentScreen instanceof FullMapScreen) return;
        MinimapConfig config = PlanetEarthMinimapClient.config;

        boolean overlayHeld = client.currentScreen == null && PlanetEarthMinimapClient.overlayMapKey.isPressed();
        // The overlay map draws its own waypoint markers on top of itself (see
        // drawMapWaypoints), so the world-projected HUD labels underneath are redundant
        // and were bleeding through the overlay's background — skip them while it's up.
        if (!overlayHeld) {
            drawWaypointHudMarkers(context);
        }
        if (config.showStatusBar) drawStatusBarOnHud(context, client, config);

        if (overlayHeld) {
            OverlayMap.render(context);
        } else if (overlayMapWasHeld) {
            // Flush the zoom level the player scrolled to while holding the key, the
            // same way the minimap editor only saves once, on close, rather than on
            // every single slider tick.
            config.save();
        }
        overlayMapWasHeld = overlayHeld;

        // While the overlay is up it already shows a much bigger view of essentially
        // the same area, usually at a different zoom — so drawing the small corner
        // minimap underneath at the same time meant fetching, decoding and drawing two
        // separate sets of map tiles every frame instead of one. Skipping the redundant
        // one while the overlay covers it anyway is a real, easy win.
        if (overlayHeld) return;

        if (!config.enabled) return;
        drawMap(context, config.x, config.y, config.width, config.height, false);
    }

    private static void drawWaypointHudMarkers(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (!config.showWaypoints || config.waypoints.isEmpty()
                || client.player == null || client.world == null) return;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = PlatformCompat.cameraPosition(camera);
        Quaternionf inverseCameraRotation = new Quaternionf(camera.getRotation()).conjugate();
        // Zoom mods narrow this well below 1 degree, so only fall back to the menu FOV
        // for genuinely broken values — treating a real zoomed-in FOV as invalid was why
        // markers used to drift toward screen centre (and appear stuck there) while zoomed.
        double currentFov = client.gameRenderer.getFov(camera, 1.0f, true);
        if (!Double.isFinite(currentFov) || currentFov <= 0.0) {
            currentFov = client.options.getFov().getValue();
        }
        double verticalFov = Math.toRadians(MathHelper.clamp(currentFov, 0.01, 179.0));
        double focalLength = screenHeight / (2.0 * Math.tan(verticalFov * 0.5));

        boolean anchoredExistingWaypoint = false;
        for (MinimapConfig.Waypoint waypoint : config.waypoints) {
            if (!Double.isFinite(waypoint.y)) {
                waypoint.y = client.player.getY();
                anchoredExistingWaypoint = true;
            }
        }
        if (anchoredExistingWaypoint) config.save();

        List<MinimapConfig.Waypoint> visible = waypointDistanceScratch;
        visible.clear();
        visible.addAll(config.waypoints);
        visible.sort(Comparator.comparingDouble((MinimapConfig.Waypoint waypoint) -> {
            double dx = waypoint.x - client.player.getX();
            double dz = waypoint.z - client.player.getZ();
            return dx * dx + dz * dz;
        }));

        int drawn = 0;
        for (MinimapConfig.Waypoint waypoint : visible) {
            if (drawn >= 12) break;
            double dx = waypoint.x - client.player.getX();
            double dz = waypoint.z - client.player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);

            Vec3d anchor = new Vec3d(waypoint.x, waypoint.y + 1.5, waypoint.z);
            ScreenPoint anchorPoint = project(anchor, cameraPos, inverseCameraRotation,
                    focalLength, screenWidth, screenHeight);
            if (anchorPoint == null) continue;

            int pinSize = MathHelper.clamp(config.waypointSize * 2, 2, 40);
            int fontHeight = client.textRenderer.fontHeight;
            int x = MathHelper.clamp((int) Math.round(anchorPoint.x),
                    pinSize / 2 + 3, screenWidth - pinSize / 2 - 3);
            int y = MathHelper.clamp((int) Math.round(anchorPoint.y),
                    pinSize + 3, screenHeight - fontHeight - 10);
            // The marker is drawn translucent (see WaypointPalette) specifically so that
            // when it lands on the crosshair — this HUD callback runs after vanilla draws
            // it — the crosshair still shows through instead of being hidden outright.
            WaypointPalette.drawMarker(context, x, y, pinSize,
                    waypoint.color, waypoint.shape);

            String label = waypointLabelText(waypoint, distance, config.waypointLabelMode);
            if (label != null) {
                float labelScale = MathHelper.clamp(config.waypointLabelScalePercent, 25, 150) / 100.0f;
                int rawTextWidth = client.textRenderer.getWidth(label);
                int textWidth = (int) Math.ceil(rawTextWidth * labelScale);
                int textHeight = (int) Math.ceil(fontHeight * labelScale);
                // The icon and label intentionally remain readable through walls, but
                // are projected from the saved world coordinate instead of a screen-fixed beam.
                int labelX = MathHelper.clamp(x - textWidth / 2, 3,
                        Math.max(3, screenWidth - textWidth - 3));
                int labelY = MathHelper.clamp(y + 3, 3,
                        Math.max(3, screenHeight - textHeight - 3));
                PlatformCompat.push(context);
                PlatformCompat.translate(context, labelX, labelY);
                PlatformCompat.scale(context, labelScale, labelScale);
                context.fill(-3, -2, rawTextWidth + 3, fontHeight + 2, 0xC0101820);
                context.drawTextWithShadow(client.textRenderer, Text.literal(label),
                        0, 0, WaypointPalette.readableTextColor(waypoint.color));
                PlatformCompat.pop(context);
            }
            drawn++;
        }
    }

    /** "820m" under 1000m, "1.2km" from 1000m up. Formatted by hand instead of
     *  String.format — this runs once per visible waypoint every single frame, and
     *  String.format re-parses its format string on every call. */
    private static String formatWaypointDistance(double distance) {
        if (distance < 1000.0) {
            return Math.round(distance) + "m";
        }
        long tenths = Math.round(distance / 100.0);
        return (tenths / 10) + "." + (tenths % 10) + "km";
    }

    /** Null means the label is switched off entirely ("none") — the marker icon still
     *  draws either way, only the text is skipped. */
    private static String waypointLabelText(MinimapConfig.Waypoint waypoint, double distance, String mode) {
        if ("none".equals(mode)) return null;
        String name = waypoint.name == null || waypoint.name.isBlank() ? "웨이포인트" : waypoint.name;
        return switch (mode) {
            case "name" -> name;
            case "distance" -> formatWaypointDistance(distance);
            default -> name + " · " + formatWaypointDistance(distance);
        };
    }

    private static ScreenPoint project(Vec3d worldPos, Vec3d cameraPos,
                                       Quaternionf inverseCameraRotation, double focalLength,
                                       int screenWidth, int screenHeight) {
        Vector3f cameraSpace = new Vector3f(
                (float) (worldPos.x - cameraPos.x),
                (float) (worldPos.y - cameraPos.y),
                (float) (worldPos.z - cameraPos.z));
        cameraSpace.rotate(inverseCameraRotation);
        // Camera#setRotation builds its forward plane from local +Z. Minecraft's
        // screen-right direction is local -X, hence the minus sign for screen X.
        double forward = cameraSpace.z;
        if (forward <= 0.05) return null;
        double screenX = screenWidth * 0.5 - cameraSpace.x * focalLength / forward;
        double screenY = screenHeight * 0.5 - cameraSpace.y * focalLength / forward;
        return new ScreenPoint(screenX, screenY, forward);
    }

    private record ScreenPoint(double x, double y, double depth) {
    }

    public static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing) {
        drawMap(context, x, y, width, height, editing,
                PlanetEarthMinimapClient.config.zoom, 0xE0153427, MINIMAP_LOADING);
    }

    /** Same rendering as the small corner minimap, but with an independently chosen
     *  zoom level and background colour — used by {@link OverlayMap} to draw a bigger
     *  version without duplicating all of this. */
    public static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing,
                               int zoom, int backgroundColor) {
        drawMap(context, x, y, width, height, editing, zoom, backgroundColor, OVERLAY_LOADING);
    }

    private static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing,
                                int zoom, int backgroundColor, LoadingState loading) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int clampedX = MathHelper.clamp(x, 0, Math.max(0, client.getWindow().getScaledWidth() - width));
        int clampedY = MathHelper.clamp(y, 0, Math.max(0, client.getWindow().getScaledHeight() - height));

        drawHotbarFrame(context, clampedX, clampedY, width, height);
        context.fill(clampedX, clampedY, clampedX + width, clampedY + height, backgroundColor);

        double playerX = client.player == null ? 0 : client.player.getX();
        double playerZ = client.player == null ? 0 : client.player.getZ();

        boolean drewMap = client.player != null && LiveAtlasTileManager.render(
                context, clampedX, clampedY, width, height,
                client.player.getX(), client.player.getZ(), zoom);

        // The most expensive optional layer: dense Towny territory near a city can mean
        // hundreds of semi-transparent fills and boundary lines redrawn every single
        // frame even while completely stationary (the cache avoids recomputing the
        // geometry, not redrawing it) — a real, reported source of steady-state FPS
        // loss, so it gets its own off switch rather than being unconditional, and
        // 저사양 모드 forces it off outright regardless of that individual setting.
        if (client.player != null && config.showAreaOverlay && !config.lowSpecMode) {
            LiveAtlasMarkerManager.renderAreaOverlay(context, clampedX, clampedY,
                    width, height, playerX, playerZ, zoom);
        }

        // The small minimap and the overlay map share one loading indicator. The Void
        // biome is forced into "incomplete" even when tiles happen to load, since those
        // tiles belong to whatever real location shares the same X/Z coordinates, not
        // to this void area — and gets its own distinct visual (the head image) rather
        // than the generic gray "로딩중" label, since it's a different situation (no
        // map for this spot at all) than an ordinary in-progress tile fetch.
        boolean voidBiome = isVoidBiome(client);
        boolean showLoading = loading.shouldShow(!drewMap || voidBiome);

        if (config.showGrid) {
            drawChunkGrid(context, clampedX, clampedY, width, height,
                    playerX, playerZ, zoom);
        }

        if (client.player != null && config.showWaypoints) {
            drawMapWaypoints(context, clampedX, clampedY, width, height,
                    playerX, playerZ, zoom);
        }

        if (client.player != null) {
            NavigationManager.renderOnMinimap(context, clampedX, clampedY,
                    width, height, playerX, playerZ, zoom);
        }

        if (showLoading) {
            if (voidBiome) {
                drawHeadFill(context, clampedX, clampedY, width, height);
            } else {
                drawLoadingIndicator(context, clampedX, clampedY, width, height);
            }
        }

        if (client.player != null) {
            int centerX = clampedX + width / 2;
            int centerY = clampedY + height / 2;
            // Other players' position markers and name tags come from a separate API
            // than the map tile images, so they were still showing up floating over the
            // head-loading indicator even when the actual map underneath wasn't there.
            if (config.showPlayers && !showLoading) {
                LiveAtlasPlayerManager.render(context, clampedX, clampedY, width, height,
                        client.player.getX(), client.player.getZ(), zoom);
            }
            drawDirectionArrow(context, centerX, centerY, client.player.getYaw());
            String direction = cardinalDirection(client.player.getYaw());
            context.drawTextWithShadow(client.textRenderer, Text.literal("N"), centerX - 3,
                    clampedY + 3, 0xFFFFFFFF);
            context.drawTextWithShadow(client.textRenderer, Text.literal(direction),
                    clampedX + width - client.textRenderer.getWidth(direction) - 4,
                    clampedY + 4, 0xFFFFFF55);
            // Hand-formatted instead of String.format: this runs every frame the
            // minimap is on screen, and String.format re-parses its pattern each call.
            String coords = Math.round(client.player.getX()) + ", " + Math.round(client.player.getZ());
            context.drawTextWithShadow(client.textRenderer, Text.literal(coords), clampedX + 4,
                    clampedY + height - client.textRenderer.fontHeight - 3, 0xFFFFFFFF);
        }

        if (editing) drawResizeHandles(context, clampedX, clampedY, width, height);
    }

    private static void drawMapWaypoints(DrawContext context, int mapX, int mapY,
                                         int width, int height, double centerWorldX,
                                         double centerWorldZ, int zoom) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        double scale = 4.0 / (1 << MathHelper.clamp(zoom, 0, 7));
        double zoomFactor = Math.pow(1.14, 3 - MathHelper.clamp(zoom, 0, 7));
        int size = MathHelper.clamp(
                (int) Math.round(config.waypointSize * 2 * zoomFactor), 2, 30);
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        int half = size / 2;
        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            for (MinimapConfig.Waypoint waypoint : config.waypoints) {
                int x = centerX + (int) Math.round((waypoint.x - centerWorldX) * scale);
                int y = centerY + (int) Math.round((waypoint.z - centerWorldZ) * scale);
                if (x < mapX - half || x > mapX + width + half
                        || y < mapY || y > mapY + height + size) continue;
                WaypointPalette.drawMarker(context, x, y, size,
                        waypoint.color, waypoint.shape);
            }
        } finally {
            context.disableScissor();
        }
    }

    /** Debounced show/hide state for one map surface's loading indicator (150ms delay
     *  to show, 300ms delay to hide, so an ordinary split-second tile fetch never
     *  flickers it on screen at all). The small minimap and the overlay map each keep a
     *  separate instance so drawing one can never flicker the other's indicator on or off. */
    private static final class LoadingState {
        private boolean visible;
        private boolean lastComplete = true;
        private long stateChangedAt;

        boolean shouldShow(boolean incomplete) {
            long now = System.nanoTime();
            boolean complete = !incomplete;
            if (complete != lastComplete) {
                lastComplete = complete;
                stateChangedAt = now;
            }
            long stableFor = now - stateChangedAt;
            if (!complete && !visible && stableFor >= 150_000_000L) {
                visible = true;
            } else if (complete && visible && stableFor >= 300_000_000L) {
                visible = false;
            }
            return visible;
        }
    }

    /** The loading indicator: a flat gray box with a "로딩중" label whose trailing dots
     *  cycle 1 → 2 → 3 → 1... every 100ms, so it visibly reads as "still working"
     *  instead of a static label. */
    private static void drawLoadingIndicator(DrawContext context, int mapX, int mapY, int width, int height) {
        context.fill(mapX, mapY, mapX + width, mapY + height, 0xFF808080);
        MinecraftClient client = MinecraftClient.getInstance();
        int dotCount = (int) ((System.nanoTime() / 100_000_000L) % 3) + 1;
        String label = "로딩중" + "...".substring(0, dotCount);
        int textWidth = client.textRenderer.getWidth(label);
        int fontHeight = client.textRenderer.fontHeight;
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        context.drawTextWithShadow(client.textRenderer, Text.literal(label),
                centerX - textWidth / 2, centerY - fontHeight / 2, 0xFFFFFFFF);
    }

    /** The Void biome's distinct loading visual: no gradient, just the head image
     *  stretched to exactly fill the box on both axes independently, so it always
     *  matches whatever aspect ratio the box currently has instead of a fixed crop. */
    private static void drawHeadFill(DrawContext context, int mapX, int mapY, int width, int height) {
        PlatformCompat.push(context);
        PlatformCompat.translate(context, (float) mapX, (float) mapY);
        PlatformCompat.scale(context, width / 600.0f, height / 600.0f);
        PlatformCompat.drawTexture(context, LOADING_HEAD, 0, 0, 0, 0, 600, 600, 600, 600);
        PlatformCompat.pop(context);
    }

    /** Draws lines on real Minecraft chunk borders (world X/Z multiples of 16). */
    private static void drawChunkGrid(DrawContext context, int mapX, int mapY, int width, int height,
                                      double centerWorldX, double centerWorldZ, int zoom) {
        double scale = 4.0 / (1 << MathHelper.clamp(zoom, 0, 7));
        double chunkPixels = 16.0 * scale;

        // Very distant zoom levels would put several chunk borders in one pixel. Skip an
        // aligned power-of-two number of chunks so the grid stays readable; every line
        // that remains is still an exact Minecraft chunk boundary.
        int strideChunks = 1;
        while (chunkPixels * strideChunks < 4.0) strideChunks *= 2;
        double worldStep = 16.0 * strideChunks;
        double minWorldX = centerWorldX - width / (2.0 * scale);
        double maxWorldX = centerWorldX + width / (2.0 * scale);
        double minWorldZ = centerWorldZ - height / (2.0 * scale);
        double maxWorldZ = centerWorldZ + height / (2.0 * scale);
        double firstX = Math.floor(minWorldX / worldStep) * worldStep;
        double firstZ = Math.floor(minWorldZ / worldStep) * worldStep;
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;

        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        for (double worldX = firstX; worldX <= maxWorldX; worldX += worldStep) {
            int screenX = centerX + (int) Math.round((worldX - centerWorldX) * scale);
            long chunkX = Math.round(worldX / 16.0);
            int color = Math.floorMod(chunkX, 16) == 0 ? 0x88FFFFFF : 0x55FFFFFF;
            context.fill(screenX, mapY, screenX + 1, mapY + height, color);
        }
        for (double worldZ = firstZ; worldZ <= maxWorldZ; worldZ += worldStep) {
            int screenY = centerY + (int) Math.round((worldZ - centerWorldZ) * scale);
            long chunkZ = Math.round(worldZ / 16.0);
            int color = Math.floorMod(chunkZ, 16) == 0 ? 0x88FFFFFF : 0x55FFFFFF;
            context.fill(mapX, screenY, mapX + width, screenY + 1, color);
        }
        context.disableScissor();
    }

    /** Pixel-style bevel matching Minecraft's classic gray hotbar/slot frame. */
    private static void drawHotbarFrame(DrawContext context, int x, int y, int width, int height) {
        context.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0xF0101010);
        context.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xFF8B8B8B);
        context.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0xFF555555);
        context.fill(x - 2, y - 2, x + width + 2, y, 0xFFC6C6C6);
        context.fill(x - 2, y - 2, x, y + height + 2, 0xFFC6C6C6);
        context.fill(x - 2, y + height, x + width + 2, y + height + 2, 0xFF373737);
        context.fill(x + width, y - 2, x + width + 2, y + height + 2, 0xFF373737);
        drawBorder(context, x - 4, y - 4, width + 8, height + 8, 0xFF000000);
        drawBorder(context, x - 1, y - 1, width + 2, height + 2, 0xFF202020);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y + 1, x + 1, y + height - 1, color);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static void drawResizeHandles(DrawContext context, int x, int y, int width, int height) {
        int color = 0xFFFFFFFF;
        int middleX = x + width / 2;
        int middleY = y + height / 2;
        context.fill(x, middleY - 9, x + 4, middleY + 9, color);
        context.fill(x + width - 4, middleY - 9, x + width, middleY + 9, color);
        context.fill(middleX - 9, y, middleX + 9, y + 4, color);
        context.fill(middleX - 9, y + height - 4, middleX + 9, y + height, color);
        context.fill(x, y, x + 7, y + 7, color);
        context.fill(x + width - 7, y, x + width, y + 7, color);
        context.fill(x, y + height - 7, x + 7, y + height, color);
        context.fill(x + width - 7, y + height - 7, x + width, y + height, color);
    }

    private static void drawDirectionArrow(DrawContext context, int x, int y, float yaw) {
        MinecraftClient client = MinecraftClient.getInstance();
        String arrow = "▲";
        float markerScale = PlanetEarthMinimapClient.config.selfMarkerSize / 9.0f;
        int arrowX = -client.textRenderer.getWidth(arrow) / 2;
        int arrowY = -client.textRenderer.fontHeight / 2;
        PlatformCompat.push(context);
        PlatformCompat.translate(context, x, y);
        PlatformCompat.rotate(context, yaw + 180.0f);
        PlatformCompat.scale(context, markerScale, markerScale);
        Text arrowText = Text.literal(arrow);
        context.drawText(client.textRenderer, arrowText, arrowX - 1, arrowY, 0xFF000000, false);
        context.drawText(client.textRenderer, arrowText, arrowX + 1, arrowY, 0xFF000000, false);
        context.drawText(client.textRenderer, arrowText, arrowX, arrowY - 1, 0xFF000000, false);
        context.drawText(client.textRenderer, arrowText, arrowX, arrowY + 1, 0xFF000000, false);
        context.drawText(client.textRenderer, arrowText, arrowX, arrowY, 0xFFFFFFFF, false);
        PlatformCompat.pop(context);
    }

    private static final java.time.ZoneId KOREA_ZONE = java.time.ZoneId.of("Asia/Seoul");
    private static String cachedBiomeName = "";
    private static long biomeComputedAt;

    /** "평원 · 오후 3시 30분 45초" style small status text — biome name plus the real
     *  Korea-time clock (not the in-game day/night cycle). The biome half is cached and
     *  refreshed a few times a second at most, since a registry lookup + translation is
     *  comparatively expensive and the biome rarely changes frame to frame; the clock
     *  half is cheap to format and is recomputed every call so the seconds stay live. */
    private static String biomeAndClockText(MinecraftClient client) {
        long now = System.nanoTime();
        if (cachedBiomeName.isEmpty() || now - biomeComputedAt >= 500_000_000L) {
            String fresh = currentBiomeName(client);
            if (!fresh.isEmpty()) {
                cachedBiomeName = fresh;
                biomeComputedAt = now;
            }
        }
        String clock = currentClockText();
        return cachedBiomeName.isEmpty() ? clock : cachedBiomeName + " · " + clock;
    }

    private static String currentBiomeName(MinecraftClient client) {
        if (client.player == null || client.world == null) return "";
        RegistryEntry<Biome> biome = client.world.getBiome(client.player.getBlockPos());
        return biome.getKey()
                .map(key -> Text.translatable("biome." + key.getValue().getNamespace()
                        + "." + key.getValue().getPath()).getString())
                .orElse("");
    }

    /** True in the "공허" (Void) biome — an area with no real terrain, where the map
     *  API can still coincidentally have tile data for whatever X/Z the player happens
     *  to be standing on (same class of bug as the Nether coordinate mismatch), showing
     *  an unrelated "working" map instead of the loading indicator. Checked by the raw
     *  biome key rather than the translated name so it doesn't depend on locale. */
    private static boolean isVoidBiome(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;
        RegistryEntry<Biome> biome = client.world.getBiome(client.player.getBlockPos());
        return biome.getKey().map(key -> "the_void".equals(key.getValue().getPath())).orElse(false);
    }

    /** Real Korea-time (Asia/Seoul) clock as a 12-hour readout with seconds, e.g.
     *  "오후 3시 30분 45초" — independent of the player's system timezone and of the
     *  in-game day/night cycle. */
    private static String currentClockText() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(KOREA_ZONE);
        int hour24 = now.getHour();
        int minute = now.getMinute();
        int second = now.getSecond();
        String period = hour24 < 12 ? "오전" : "오후";
        int hour12 = hour24 % 12;
        if (hour12 == 0) hour12 = 12;
        // Hand-formatted instead of String.format: the clock is deliberately
        // recomputed every single call (see biomeAndClockText) so the seconds stay
        // live, and String.format re-parses its pattern string on every call.
        StringBuilder text = new StringBuilder(16);
        text.append(period).append(' ').append(hour12).append("시 ");
        appendTwoDigits(text, minute).append("분 ");
        appendTwoDigits(text, second).append("초");
        return text.toString();
    }

    private static StringBuilder appendTwoDigits(StringBuilder text, int value) {
        if (value < 10) text.append('0');
        return text.append(value);
    }

    private static void drawStatusBarOnHud(DrawContext context, MinecraftClient client, MinimapConfig config) {
        int barWidth = statusBarWidth();
        int barHeight = statusBarHeight();
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int x = MathHelper.clamp(config.statusBarX, 0, Math.max(0, screenWidth - barWidth));
        int y = MathHelper.clamp(config.statusBarY, 0, Math.max(0, screenHeight - barHeight));
        drawStatusBar(context, x, y);
    }

    private static float statusBarScale() {
        return MathHelper.clamp(PlanetEarthMinimapClient.config.statusBarScalePercent, 50, 200) / 100.0f;
    }

    /** Width of the standalone, freely repositionable biome/clock bar (see
     *  {@link OverlayMap} for the similar hold-key map, and {@link MinimapEditorScreen}
     *  for where this bar is dragged into place). Grows and shrinks with the current
     *  biome name and the configured scale, so callers must re-measure every frame
     *  rather than caching it. */
    public static int statusBarWidth() {
        MinecraftClient client = MinecraftClient.getInstance();
        int rawWidth = client.textRenderer.getWidth(biomeAndClockText(client)) + 10;
        return Math.round(rawWidth * statusBarScale());
    }

    public static int statusBarHeight() {
        int rawHeight = MinecraftClient.getInstance().textRenderer.fontHeight + 6;
        return Math.round(rawHeight * statusBarScale());
    }

    public static void drawStatusBar(DrawContext context, int x, int y) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        String text = biomeAndClockText(client);
        if (text.isEmpty()) return;
        float scale = statusBarScale();
        int width = statusBarWidth();
        int height = statusBarHeight();
        context.fill(x, y, x + width, y + height, 0xC0101820);
        if (Math.abs(scale - 1.0f) < 0.001f) {
            // At the default 100% there is nothing to scale, so skip the matrix path
            // below entirely: routing the bitmap font through even an identity-ish
            // scale transform made it come out soft/doubled-looking instead of crisp.
            context.drawTextWithShadow(client.textRenderer, Text.literal(text),
                    x + 5, y + (height - client.textRenderer.fontHeight) / 2, 0xFFFFFFFF);
            return;
        }
        PlatformCompat.push(context);
        PlatformCompat.translate(context, Math.round(x + 5 * scale),
                Math.round(y + (height - client.textRenderer.fontHeight * scale) / 2f));
        PlatformCompat.scale(context, scale, scale);
        context.drawTextWithShadow(client.textRenderer, Text.literal(text), 0, 0, 0xFFFFFFFF);
        PlatformCompat.pop(context);
    }

    private static String cardinalDirection(float yaw) {
        String[] directions = {"남", "남서", "서", "북서", "북", "북동", "동", "남동"};
        return directions[Math.floorMod(Math.round(yaw / 45.0f), 8)];
    }
}
