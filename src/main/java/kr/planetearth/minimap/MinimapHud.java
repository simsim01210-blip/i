package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class MinimapHud {
    // The small corner minimap and the "hold G" overlay map can both be on screen in
    // the same frame and cover different areas, so each needs its own independent
    // loading-indicator state. Sharing one used to make the indicator flicker: whichever
    // of the two rendered last each frame would stomp on the other's show/hide timer.
    private static final LoadingState MINIMAP_LOADING = new LoadingState();
    private static final LoadingState OVERLAY_LOADING = new LoadingState();
    private static SimpleFramebuffer circularMapTarget;
    private static boolean renderingCircularTarget;
    private static boolean overlayMapWasHeld;
    private static final int MAX_HUD_WAYPOINTS = 12;
    // Reused every frame instead of allocating a fresh ArrayList to sort-by-distance
    // in, since this runs unconditionally whenever any waypoints are shown at all.
    private static final List<MinimapConfig.Waypoint> waypointDistanceScratch = new ArrayList<>();
    private static final Quaternionf CAMERA_ROTATION_SCRATCH = new Quaternionf();
    private static final Vector3f CAMERA_SPACE_SCRATCH = new Vector3f();
    private static final double[] PROJECTED_POINT_SCRATCH = new double[2];
    private static final int[] EDGE_POINT_SCRATCH = new int[2];
    // 1/tan(fov/2) of the projection the world was actually drawn with this frame,
    // captured from WorldRenderEvents. Reading it back means the waypoint labels use
    // exactly the FOV every zoom/FOV mod already settled on, without calling
    // GameRenderer#getFov a second time per frame — which re-ran those mods' own FOV
    // hooks (some keep smoothing state in there) with a made-up tickDelta.
    private static float worldProjectionScaleY = Float.NaN;
    private static long worldProjectionNanos;
    private static final long WORLD_PROJECTION_MAX_AGE_NANOS = 250_000_000L;
    private static final String ARROW_GLYPH = "▲";
    private static final Text ARROW_TEXT = Text.literal(ARROW_GLYPH);
    // The four fixed compass points ringing the map edge — separate from
    // DIRECTION_TEXTS below, which is the single dynamic "which way am I currently
    // facing" readout. Base bearing is clockwise from north (0/90/180/270) to match
    // both the existing yaw convention and the sin/cos ring-position math.
    private static final String[] COMPASS_LABELS = {"N", "E", "S", "W"};
    private static final Text[] COMPASS_TEXTS = {
            Text.literal("N"), Text.literal("E"), Text.literal("S"), Text.literal("W")
    };
    private static final float[] COMPASS_BEARINGS = {0f, 90f, 180f, 270f};
    private static final String[] DIRECTIONS = {"남", "남서", "서", "북서", "북", "북동", "동", "남동"};
    private static final Text[] DIRECTION_TEXTS = {
            Text.literal("남"), Text.literal("남서"), Text.literal("서"), Text.literal("북서"),
            Text.literal("북"), Text.literal("북동"), Text.literal("동"), Text.literal("남동")
    };
    private static final String[] LOADING_LABELS = {"로딩중.", "로딩중..", "로딩중..."};
    private static final Text[] LOADING_TEXTS = {
            Text.literal(LOADING_LABELS[0]), Text.literal(LOADING_LABELS[1]), Text.literal(LOADING_LABELS[2])
    };
    private static final double[] WAYPOINT_ZOOM_FACTORS = {
            Math.pow(1.14, 3), Math.pow(1.14, 2), 1.14, 1.0,
            1.0 / 1.14, 1.0 / (1.14 * 1.14), 1.0 / Math.pow(1.14, 3), 1.0 / Math.pow(1.14, 4)
    };
    private static long cachedCoordinateX = Long.MIN_VALUE;
    private static long cachedCoordinateY = Long.MIN_VALUE;
    private static long cachedCoordinateZ = Long.MIN_VALUE;
    private static Text cachedCoordinateText = Text.literal("");
    // Pre-baked at ~32% opacity into the PNG itself (see the asset's generation
    // script) rather than tinted at draw time — RenderSystem.setShaderColor alpha
    // tinting on drawTexture has a documented history of rendering broken in this
    // codebase, so a static faint asset sidesteps that entirely.
    private static final Identifier HOTBAR_WATERMARK = new Identifier(
            PlanetEarthMinimapClient.MOD_ID, "textures/gui/hotbar_watermark.png");

    private MinimapHud() {}

    static void captureWorldProjection(Matrix4f projection) {
        if (projection == null) return;
        float scaleY = Math.abs(projection.m11());
        if (!Float.isFinite(scaleY) || scaleY <= 0f) return;
        worldProjectionScaleY = scaleY;
        worldProjectionNanos = System.nanoTime();
    }

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

        drawHotbarWatermark(context, client);

        boolean overlayHeld = OverlayMap.isActive(client);
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
        // Holding Tab tucks the minimap away while the player list is up, so the two
        // don't pile on top of each other (both like the top of the screen).
        if (client.currentScreen == null && client.options.playerListKey.isPressed()) return;
        drawMap(context, config.x, config.y, config.width, config.height, false);
    }

    /** A faint personal watermark drawn over the 9th (last) hotbar slot. Uses the
     *  standard vanilla hotbar geometry (182x22 background centred at the bottom of
     *  the screen, 20px-wide slots, 16x16 icon inset by 3px) rather than reading the
     *  actual hotbar widget position, since drawing here doesn't need to react to a
     *  held item — it just needs to line up with where slot 9 always is. Only hides
     *  with the same hudHidden/F1 and menu-screen checks the rest of this HUD uses;
     *  it isn't tied to config.enabled since it isn't part of the minimap itself. */
    private static void drawHotbarWatermark(DrawContext context, MinecraftClient client) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int slotX = screenWidth / 2 - 91 + 8 * 20 + 3;
        int slotY = screenHeight - 22 + 3;
        PlatformCompat.drawTranslucentTexture(context, HOTBAR_WATERMARK, slotX, slotY, 0, 0, 16, 16, 16, 16);
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
        Quaternionf inverseCameraRotation = CAMERA_ROTATION_SCRATCH
                .set(camera.getRotation()).conjugate();
        double focalLength;
        if (Float.isFinite(worldProjectionScaleY)
                && System.nanoTime() - worldProjectionNanos < WORLD_PROJECTION_MAX_AGE_NANOS) {
            focalLength = screenHeight * 0.5 * worldProjectionScaleY;
        } else {
            // Fallback for a renderer that skips Fabric's world render events.
            // Zoom mods narrow this well below 1 degree, so only fall back to the menu FOV
            // for genuinely broken values — treating a real zoomed-in FOV as invalid was why
            // markers used to drift toward screen centre (and appear stuck there) while zoomed.
            double currentFov = client.gameRenderer.getFov(camera, 1.0f, true);
            if (!Double.isFinite(currentFov) || currentFov <= 0.0) {
                currentFov = client.options.getFov().getValue();
            }
            double verticalFov = Math.toRadians(MathHelper.clamp(currentFov, 0.01, 179.0));
            focalLength = screenHeight / (2.0 * Math.tan(verticalFov * 0.5));
        }

        boolean anchoredExistingWaypoint = false;
        for (MinimapConfig.Waypoint waypoint : config.waypoints) {
            if (!Double.isFinite(waypoint.y)
                    || waypoint.y == MinimapConfig.UNKNOWN_WAYPOINT_Y) {
                waypoint.y = client.player.getY();
                anchoredExistingWaypoint = true;
            }
        }
        if (anchoredExistingWaypoint) config.save();

        List<MinimapConfig.Waypoint> visible = waypointDistanceScratch;
        visible.clear();
        double playerX = client.player.getX();
        double playerZ = client.player.getZ();
        // Only twelve markers can be drawn. Keep a tiny sorted window instead of
        // copying and TimSorting the complete waypoint list every rendered frame.
        for (MinimapConfig.Waypoint waypoint : config.waypoints) {
            double dx = waypoint.x - playerX;
            double dz = waypoint.z - playerZ;
            double distanceSquared = dx * dx + dz * dz;
            int insertion = 0;
            while (insertion < visible.size()) {
                MinimapConfig.Waypoint current = visible.get(insertion);
                double currentDx = current.x - playerX;
                double currentDz = current.z - playerZ;
                if (distanceSquared < currentDx * currentDx + currentDz * currentDz) break;
                insertion++;
            }
            if (insertion >= MAX_HUD_WAYPOINTS) continue;
            visible.add(insertion, waypoint);
            if (visible.size() > MAX_HUD_WAYPOINTS) visible.remove(MAX_HUD_WAYPOINTS);
        }

        int drawn = 0;
        for (MinimapConfig.Waypoint waypoint : visible) {
            if (drawn >= MAX_HUD_WAYPOINTS) break;
            double dx = waypoint.x - playerX;
            double dz = waypoint.z - playerZ;
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (!project(waypoint.x, waypoint.y + 1.5, waypoint.z,
                    cameraPos, inverseCameraRotation, focalLength,
                    screenWidth, screenHeight, PROJECTED_POINT_SCRATCH)) continue;

            int pinSize = MathHelper.clamp(config.waypointSize * 2, 2, 40);
            int fontHeight = client.textRenderer.fontHeight;
            int x = MathHelper.clamp((int) Math.round(PROJECTED_POINT_SCRATCH[0]),
                    pinSize / 2 + 3, screenWidth - pinSize / 2 - 3);
            int y = MathHelper.clamp((int) Math.round(PROJECTED_POINT_SCRATCH[1]),
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

    private static boolean project(double worldX, double worldY, double worldZ,
                                   Vec3d cameraPos, Quaternionf inverseCameraRotation,
                                   double focalLength, int screenWidth, int screenHeight,
                                   double[] output) {
        Vector3f cameraSpace = CAMERA_SPACE_SCRATCH.set(
                (float) (worldX - cameraPos.x),
                (float) (worldY - cameraPos.y),
                (float) (worldZ - cameraPos.z));
        cameraSpace.rotate(inverseCameraRotation);
        // Camera#setRotation builds its forward plane from local +Z. Minecraft's
        // screen-right direction is local -X, hence the minus sign for screen X.
        double forward = cameraSpace.z;
        if (forward <= 0.05) return false;
        output[0] = screenWidth * 0.5 - cameraSpace.x * focalLength / forward;
        output[1] = screenHeight * 0.5 - cameraSpace.y * focalLength / forward;
        return true;
    }

    public static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing) {
        drawMap(context, x, y, width, height, editing,
                PlanetEarthMinimapClient.config.zoom, 0xE0153427,
                MINIMAP_LOADING, false, true);
    }

    /** Same rendering as the small corner minimap, but with an independently chosen
     *  zoom level and background colour — used by {@link OverlayMap} to draw a bigger
     *  version without duplicating all of this. */
    public static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing,
                               int zoom, int backgroundColor) {
        drawMap(context, x, y, width, height, editing, zoom, backgroundColor,
                OVERLAY_LOADING, true, false);
    }

    private static void drawMap(DrawContext context, int x, int y, int width, int height, boolean editing,
                                int zoom, int backgroundColor, LoadingState loading,
                                boolean highPriorityTiles, boolean allowCircularShape) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        // Both the circular framebuffer pass and rotation's oversized content box are
        // real extra GPU/CPU cost on top of the ordinary square map — 저사양 모드 is
        // the player's explicit "keep this cheap and stable" switch, so it overrides
        // both regardless of their own toggles, the same way it already forces the
        // (much older, already known to be heavy) territory colour overlay off below.
        boolean circularShape = allowCircularShape && config.circularShape && !config.lowSpecMode;
        int frameMargin = circularShape ? 4 : 0;
        int maxX = client.getWindow().getScaledWidth() - width - frameMargin;
        int maxY = client.getWindow().getScaledHeight() - height - frameMargin;
        int minX = maxX >= frameMargin ? frameMargin : 0;
        int minY = maxY >= frameMargin ? frameMargin : 0;
        int clampedX = MathHelper.clamp(x, minX, Math.max(minX, maxX));
        int clampedY = MathHelper.clamp(y, minY, Math.max(minY, maxY));

        // A solid-colour corner mask only makes a circle look round against the same
        // colour; over the game it leaves a conspicuous square behind it. Render the
        // ordinary map once into a transparent target and composite only its circular
        // portion instead. All tiles, overlays, labels and loading UI are clipped in a
        // single GPU pass, without redrawing the map once per scanline.
        if (circularShape && !renderingCircularTarget) {
            drawCircularMap(context, clampedX, clampedY, width, height, editing,
                    zoom, backgroundColor, loading, highPriorityTiles);
            return;
        }

        if (!circularShape) {
            drawHotbarFrame(context, clampedX, clampedY, width, height);
        }
        context.fill(clampedX, clampedY, clampedX + width, clampedY + height, backgroundColor);

        double playerX = client.player == null ? 0 : client.player.getX();
        double playerZ = client.player == null ? 0 : client.player.getZ();
        int centerX = clampedX + width / 2;
        int centerY = clampedY + height / 2;

        // "회전" spins the map itself so the player's current facing is always up,
        // instead of true north. Only the tile mosaic and the chunk grid go through an
        // actual rotated matrix below — they're plain image/line content with no
        // "upright" concern. Everything else (territory fills keep their own inner
        // matrix scope; markers, waypoints, players, navigation, and every label) instead
        // rotates its own screen *position* by the same angle via rotateOffset() and
        // draws unrotated, so icons land in the correct spun spot while names and text
        // stay flat and readable instead of spinning — and tipping/flipping upside down
        // — with the map. None of this is extra geometry or draw calls, just the same
        // matrix multiply (or, off the matrix stack, the same amount of trig) already
        // happening for the direction arrow and waypoint label scaling every frame.
        boolean rotating = config.rotateWithPlayer && client.player != null && !config.lowSpecMode;
        float contentRotation = rotating ? -(client.player.getYaw() + 180.0f) : 0f;

        // Rotating an image with the original viewport dimensions leaves triangular
        // holes at the frame corners. Render the inverse-rotated viewport's bounding
        // rectangle instead. This is the minimum extra area needed for the current
        // angle (rather than always paying for a full diagonal-sized square).
        int contentX = clampedX;
        int contentY = clampedY;
        int contentWidth = width;
        int contentHeight = height;
        if (rotating) {
            double radians = Math.toRadians(contentRotation);
            double absCos = Math.abs(Math.cos(radians));
            double absSin = Math.abs(Math.sin(radians));
            contentWidth = evenCeiling(width * absCos + height * absSin + 2.0);
            contentHeight = evenCeiling(width * absSin + height * absCos + 2.0);
            contentX = centerX - contentWidth / 2;
            contentY = centerY - contentHeight / 2;
        }

        // Expanded tiles must cover the rotated corners but never bleed past the
        // actual configured frame into the game HUD. Both the scissor and the matrix
        // push live on the DrawContext that every other mod's HUD shares this frame,
        // so each is released in a finally — a leftover clip or transform would
        // otherwise displace whatever those mods draw after us.
        if (rotating) context.enableScissor(clampedX, clampedY, clampedX + width, clampedY + height);
        boolean drewMap;
        try {
            PlatformCompat.push(context);
            try {
                if (rotating) {
                    PlatformCompat.translate(context, centerX, centerY);
                    PlatformCompat.rotate(context, contentRotation);
                    PlatformCompat.translate(context, -centerX, -centerY);
                }
                drewMap = client.player != null && LiveAtlasTileManager.render(
                        context, contentX, contentY, contentWidth, contentHeight,
                        client.player.getX(), client.player.getZ(), zoom, highPriorityTiles);
                if (config.showGrid) {
                    drawChunkGrid(context, contentX, contentY, contentWidth, contentHeight,
                            playerX, playerZ, zoom);
                }
            } finally {
                PlatformCompat.pop(context);
            }
        } finally {
            if (rotating) context.disableScissor();
        }

        // The most expensive optional layer: dense Towny territory near a city can mean
        // hundreds of semi-transparent fills and boundary lines redrawn every single
        // frame even while completely stationary (the cache avoids recomputing the
        // geometry, not redrawing it) — a real, reported source of steady-state FPS
        // loss, so it gets its own off switch rather than being unconditional, and
        // 저사양 모드 forces it off outright regardless of that individual setting.
        if (client.player != null && config.showAreaOverlay && !config.lowSpecMode) {
            // Also needs the rotated corners covered (see contentX/contentY above), but
            // NOT contentWidth/contentHeight itself: that box is deliberately the
            // tightest one for the *exact current* angle, which is perfect for the tile
            // mosaic (cheap to redraw, keyed by world tile coordinates either way) but
            // poison for this layer's own cache — AreaRenderCache only hits on an exact
            // width/height match, so a box that reshapes by a pixel or two every single
            // frame while simply turning the camera defeated the cache completely and
            // forced a full territory-fill recompute (the most expensive layer in the
            // whole mod) every frame, not just when actually panning. A full
            // diagonal-sized square is bigger than strictly needed at most angles, but
            // it's constant while rotating, so the cache keeps hitting across frames.
            int areaBoxX = clampedX;
            int areaBoxY = clampedY;
            int areaBoxWidth = width;
            int areaBoxHeight = height;
            if (rotating) {
                int diagonal = evenCeiling(
                        Math.sqrt((double) width * width + (double) height * height) + 2.0);
                areaBoxWidth = diagonal;
                areaBoxHeight = diagonal;
                areaBoxX = centerX - diagonal / 2;
                areaBoxY = centerY - diagonal / 2;
            }
            if (rotating) context.enableScissor(clampedX, clampedY, clampedX + width, clampedY + height);
            try {
                LiveAtlasMarkerManager.renderAreaOverlay(context, areaBoxX, areaBoxY,
                        areaBoxWidth, areaBoxHeight, playerX, playerZ, zoom, contentRotation);
            } finally {
                if (rotating) context.disableScissor();
            }
        }

        // The small minimap and the overlay map share one loading indicator. Used to
        // also force this on for the Void biome, back when every non-overworld
        // dimension shared the overworld's tile coordinate space and could show an
        // unrelated "loaded-looking" map by coincidence. Now that each dimension
        // (world, worldpvp, ...) fetches from its own tile namespace, drewMap already
        // reflects reality correctly — World PvP's void-biome terrain has its own real
        // map and should just show it, not the loading indicator.
        // With the web map down nothing missing is going to arrive, so the loading
        // indicator would just sit on top of the terrain that *is* already cached —
        // keep showing that instead and say why it isn't updating.
        boolean webMapDown = WebMapHealth.isDown();
        boolean showLoading = loading.shouldShow(!webMapDown && !drewMap);

        if (client.player != null && config.showWaypoints) {
            drawMapWaypoints(context, clampedX, clampedY, width, height,
                    playerX, playerZ, zoom, contentRotation);
        }

        if (client.player != null) {
            NavigationManager.renderOnMinimap(context, clampedX, clampedY,
                    width, height, playerX, playerZ, zoom, contentRotation);
        }

        if (showLoading) {
            drawLoadingIndicator(context, clampedX, clampedY, width, height);
        }

        if (client.player != null && config.showPlayers && !showLoading) {
            // Other players' position markers and name tags come from a separate API
            // than the map tile images, so they were still showing up floating over the
            // head-loading indicator even when the actual map underneath wasn't there.
            LiveAtlasPlayerManager.render(context, clampedX, clampedY, width, height,
                    client.player.getX(), client.player.getZ(), zoom, contentRotation);
        }

        if (client.player != null) {
            // The arrow always represents "forward", so while rotating it stays fixed
            // pointing straight up instead of turning with the player — the map spins
            // underneath it instead. 180 cancels out drawDirectionArrow's own +180, so
            // the glyph ends up with zero net rotation regardless of real facing.
            drawDirectionArrow(context, centerX, centerY, rotating ? 180f : client.player.getYaw());
            int directionIndex = cardinalDirectionIndex(client.player.getYaw());
            String direction = DIRECTIONS[directionIndex];
            int halfWidth = width / 2;
            int halfHeight = height / 2;
            int[] point = EDGE_POINT_SCRATCH;

            // The four fixed compass points, always projected onto the map's actual
            // edge — the circle's rim when circularShape is on, otherwise the real
            // square border via projectToMapEdge, so they never float short of a
            // square's corners at a diagonal bearing the way a fixed circular radius
            // would. Bearing 0 (north) is wherever it currently sits on screen: fixed
            // at top when not rotating, or wherever contentRotation has spun it to.
            for (int i = 0; i < COMPASS_BEARINGS.length; i++) {
                float bearing = COMPASS_BEARINGS[i] + contentRotation;
                projectToMapEdge(centerX, centerY, halfWidth, halfHeight,
                        circularShape, bearing, 10, point);
                int labelWidth = TextWidthCache.width(COMPASS_LABELS[i]);
                context.drawTextWithShadow(client.textRenderer, COMPASS_TEXTS[i],
                        point[0] - labelWidth / 2,
                        point[1] - client.textRenderer.fontHeight / 2, 0xFFFFFFFF);
            }

            // The dynamic "which way am I currently facing" readout — a different thing
            // from the fixed N/E/S/W ring above. In square shape it keeps its original
            // corner spot (nothing else uses that corner there); in circular shape that
            // corner is trimmed away by the mask below, and the ring already occupies
            // the 12/3/6/9 points, so it moves to the empty south-east gap between them.
            if (circularShape) {
                projectToMapEdge(centerX, centerY, halfWidth, halfHeight, true,
                        135f + contentRotation, 10, point);
                context.drawTextWithShadow(client.textRenderer, DIRECTION_TEXTS[directionIndex],
                        point[0] - TextWidthCache.width(direction) / 2,
                        point[1] - client.textRenderer.fontHeight / 2, 0xFFFFFF55);
            } else {
                context.drawTextWithShadow(client.textRenderer, DIRECTION_TEXTS[directionIndex],
                        clampedX + width - TextWidthCache.width(direction) - 4,
                        clampedY + 4, 0xFFFFFF55);
            }

            // Hand-formatted instead of String.format: this runs every frame the
            // minimap is on screen, and String.format re-parses its pattern each call.
            Text coords = coordinateText(client.player.getX(), client.player.getY(), client.player.getZ());
            if (circularShape) {
                // Coordinates are HUD information, not part of the rotating map. Keep
                // them fixed in the lower interior of the circle and choose the lowest
                // row whose chord is still wide enough for the complete coordinate
                // string. This prevents both orbiting and edge clipping on small maps.
                int coordinateWidth = client.textRenderer.getWidth(coords);
                int innerRadius = Math.max(1, Math.min(halfWidth, halfHeight) - 7);
                int requiredHalfWidth = coordinateWidth / 2 + 4;
                int desiredOffset = Math.max(0,
                        innerRadius - client.textRenderer.fontHeight - 10);
                int safeOffset = 0;
                if (requiredHalfWidth < innerRadius) {
                    safeOffset = (int) Math.floor(Math.sqrt(
                            (double) innerRadius * innerRadius
                                    - (double) requiredHalfWidth * requiredHalfWidth));
                }
                int coordinateY = centerY + Math.min(desiredOffset, safeOffset)
                        - client.textRenderer.fontHeight / 2;
                context.drawTextWithShadow(client.textRenderer, coords,
                        centerX - coordinateWidth / 2, coordinateY, 0xFFFFFFFF);
            } else {
                context.drawTextWithShadow(client.textRenderer, coords, clampedX + 4,
                        clampedY + height - client.textRenderer.fontHeight - 3, 0xFFFFFFFF);
            }
        }

        if (showWebMapNotice()) {
            drawWebMapDownNotice(context, clampedX, clampedY, width, height);
        }

        // Trims the square map down to a circle by painting the same solid colour
        // that's already behind everything (the background fill above) back over the
        // four corners in a stepped approximation — a handful of extra context.fill
        // calls, the same technique the hotbar frame's bevel already builds itself
        // from, rather than a true per-pixel clip (which DrawContext's scissor can't
        // express — it's rectangle-only — without redrawing the whole map dozens of
        // times per frame to fake it).
        if (editing) drawResizeHandles(context, clampedX, clampedY, width, height);
    }

    private static void drawCircularMap(DrawContext context, int x, int y, int width, int height,
                                        boolean editing, int zoom, int backgroundColor,
                                        LoadingState loading, boolean highPriorityTiles) {
        try {
            drawCircularMapUnsafe(context, x, y, width, height, editing, zoom,
                    backgroundColor, loading, highPriorityTiles);
        } catch (Throwable error) {
            // An extra full-window-sized framebuffer is real GPU memory and driver
            // surface area a weak or old graphics card may simply not have to spare —
            // this is a real-config toggle players choose themselves, so a failure here
            // should turn itself off and fall back to the plain square map instead of
            // taking the whole game down with it.
            PlanetEarthMinimapClient.LOGGER.warn(
                    "원형 미니맵 렌더링 실패, 사각형으로 되돌립니다", error);
            renderingCircularTarget = false;
            if (circularMapTarget != null) {
                try {
                    circularMapTarget.delete();
                } catch (Throwable cleanupError) {
                    PlanetEarthMinimapClient.LOGGER.debug(
                            "Could not clean up circular minimap framebuffer", cleanupError);
                }
                circularMapTarget = null;
            }
            PlanetEarthMinimapClient.config.circularShape = false;
            PlanetEarthMinimapClient.config.save();
            drawMap(context, x, y, width, height, editing, zoom, backgroundColor,
                    loading, highPriorityTiles, false);
        }
    }

    private static void drawCircularMapUnsafe(DrawContext context, int x, int y, int width, int height,
                                              boolean editing, int zoom, int backgroundColor,
                                              LoadingState loading, boolean highPriorityTiles) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Width and height can be resized independently in the editor. A circular map
        // must nevertheless use one diameter everywhere; otherwise the source map is
        // laid out as a rectangle and only cropped as a circle at the very end, which
        // makes its apparent scale/centre look wrong. Centre the largest fitting square
        // inside the configured bounds and use it for every part of this render pass.
        int diameter = Math.max(1, Math.min(width, height));
        int circleX = x + (width - diameter) / 2;
        int circleY = y + (height - diameter) / 2;
        Framebuffer mainTarget = client.getFramebuffer();
        int framebufferWidth = client.getWindow().getFramebufferWidth();
        int framebufferHeight = client.getWindow().getFramebufferHeight();
        if (circularMapTarget == null) {
            circularMapTarget = new SimpleFramebuffer(framebufferWidth, framebufferHeight,
                    false, MinecraftClient.IS_SYSTEM_MAC);
            circularMapTarget.setClearColor(0f, 0f, 0f, 0f);
        } else if (circularMapTarget.textureWidth != framebufferWidth
                || circularMapTarget.textureHeight != framebufferHeight) {
            circularMapTarget.resize(framebufferWidth, framebufferHeight, MinecraftClient.IS_SYSTEM_MAC);
            circularMapTarget.setClearColor(0f, 0f, 0f, 0f);
        }

        // Flush anything queued for the game framebuffer before switching targets.
        context.draw();
        circularMapTarget.clear(MinecraftClient.IS_SYSTEM_MAC);
        circularMapTarget.beginWrite(false);
        renderingCircularTarget = true;
        try {
            // The circular layout rules remain enabled inside this pass. Only the old
            // colour-mask/frame step is bypassed by renderingCircularTarget.
            drawMap(context, circleX, circleY, diameter, diameter, false, zoom, backgroundColor,
                    loading, highPriorityTiles, true);
            context.draw();
        } finally {
            renderingCircularTarget = false;
            circularMapTarget.endWrite();
            mainTarget.beginWrite(false);
        }

        int centerX = circleX + diameter / 2;
        int centerY = circleY + diameter / 2;
        int radius = diameter / 2;
        PlatformCompat.drawFramebufferCircle(context, circularMapTarget.getColorAttachment(),
                centerX, centerY, radius,
                client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        drawCircularFrame(context, circleX, circleY, diameter, diameter);
        if (editing) drawResizeHandles(context, circleX, circleY, diameter, diameter);
    }

    private static int evenCeiling(double value) {
        int rounded = (int) Math.ceil(value);
        return (rounded + 1) & ~1;
    }

    /** Round counterpart to {@link #drawHotbarFrame}. Unlike filled disks, these are
     *  true rings, so this bevel can be rendered after the map without covering it. */
    private static void drawCircularFrame(DrawContext context, int mapX, int mapY, int width, int height) {
        int radius = Math.min(width, height) / 2;
        if (radius <= 0) return;
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        PlatformCompat.drawCircularHotbarFrame(context, centerX, centerY, radius);
    }

    private static void drawMapWaypoints(DrawContext context, int mapX, int mapY,
                                         int width, int height, double centerWorldX,
                                         double centerWorldZ, int zoom, float rotationDegrees) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        double scale = 4.0 / (1 << MathHelper.clamp(zoom, 0, 7));
        double zoomFactor = WAYPOINT_ZOOM_FACTORS[MathHelper.clamp(zoom, 0, 7)];
        int size = MathHelper.clamp(
                (int) Math.round(config.waypointSize * 2 * zoomFactor), 2, 30);
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        int half = size / 2;
        double[] rotated = new double[2];
        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            for (MinimapConfig.Waypoint waypoint : config.waypoints) {
                rotateOffset((waypoint.x - centerWorldX) * scale,
                        (waypoint.z - centerWorldZ) * scale, rotationDegrees, rotated);
                int x = centerX + (int) Math.round(rotated[0]);
                int y = centerY + (int) Math.round(rotated[1]);
                if (x < mapX - half || x > mapX + width + half
                        || y < mapY - half || y > mapY + height + half) continue;
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

    private static final Text WEB_MAP_DOWN_TEXT = Text.literal("웹지도 연결이 원활하지 않습니다");
    private static Text webMapVerifyText;
    private static String webMapVerifyKey;
    private static final Text WEB_MAP_NEEDS_MCEF_TEXT = Text.literal("웹지도가 인증으로 막힘 · MCEF 모드 필요");
    private static List<net.minecraft.text.OrderedText> webMapDownLines = List.of();
    private static int webMapDownLinesWidth = -1;
    private static Text webMapDownLinesText;

    /** Whether the connection notice should be up at all. */
    static boolean showWebMapNotice() {
        return WebMapHealth.isDown() || WebMapBrowser.verificationNeeded();
    }

    private static Text webMapNoticeText() {
        if (WebMapBrowser.verificationNeeded()) {
            // Names the key the player actually bound; rebuilt only when it changes so
            // the wrapped-lines cache (keyed on this Text instance) keeps hitting.
            String key = WebMapBrowser.fullMapKeyName();
            if (webMapVerifyText == null || !key.equals(webMapVerifyKey)) {
                webMapVerifyKey = key;
                webMapVerifyText = Text.literal("웹지도 인증이 필요합니다 (" + key + " → 웹지도 인증)");
            }
            return webMapVerifyText;
        }
        if (WebMapBrowser.challengeSeen() && !WebMapBrowser.mcefInstalled()) return WEB_MAP_NEEDS_MCEF_TEXT;
        return WEB_MAP_DOWN_TEXT;
    }

    /** Shown over the (still drawn, last cached) map while {@link WebMapHealth} judges
     *  the web map down. Wrapped to the map's width so it also fits the small minimap;
     *  the wrap is cached per width instead of redone every frame. Shared with the
     *  full map screen. */
    static void drawWebMapDownNotice(DrawContext context, int mapX, int mapY, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        int maxTextWidth = Math.max(40, width - 16);
        Text noticeText = webMapNoticeText();
        if (maxTextWidth != webMapDownLinesWidth || noticeText != webMapDownLinesText) {
            webMapDownLines = client.textRenderer.wrapLines(noticeText, maxTextWidth);
            webMapDownLinesWidth = maxTextWidth;
            webMapDownLinesText = noticeText;
        }
        int fontHeight = client.textRenderer.fontHeight;
        int lineHeight = fontHeight + 1;
        int blockHeight = webMapDownLines.size() * lineHeight - 1;
        int blockWidth = 0;
        for (net.minecraft.text.OrderedText line : webMapDownLines) {
            blockWidth = Math.max(blockWidth, client.textRenderer.getWidth(line));
        }
        int centerX = mapX + width / 2;
        // Upper part of the map: clear of the self arrow at the centre and of the
        // coordinates along the bottom edge, and still inside a circular frame.
        int top = mapY + Math.max(14, height / 4 - blockHeight / 2);
        context.fill(centerX - blockWidth / 2 - 4, top - 3,
                centerX + (blockWidth + 1) / 2 + 4, top + blockHeight + 3, 0xD0301010);
        for (int i = 0; i < webMapDownLines.size(); i++) {
            net.minecraft.text.OrderedText line = webMapDownLines.get(i);
            context.drawTextWithShadow(client.textRenderer, line,
                    centerX - client.textRenderer.getWidth(line) / 2,
                    top + i * lineHeight, 0xFFFFB0A0);
        }
    }

    /** The loading indicator: a flat gray box with a "로딩중" label whose trailing dots
     *  cycle 1 → 2 → 3 → 1... every 100ms, so it visibly reads as "still working"
     *  instead of a static label. */
    private static void drawLoadingIndicator(DrawContext context, int mapX, int mapY, int width, int height) {
        context.fill(mapX, mapY, mapX + width, mapY + height, 0xFF808080);
        MinecraftClient client = MinecraftClient.getInstance();
        int labelIndex = (int) ((System.nanoTime() / 100_000_000L) % 3);
        Text label = LOADING_TEXTS[labelIndex];
        int textWidth = client.textRenderer.getWidth(LOADING_LABELS[labelIndex]);
        int fontHeight = client.textRenderer.fontHeight;
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        context.drawTextWithShadow(client.textRenderer, label,
                centerX - textWidth / 2, centerY - fontHeight / 2, 0xFFFFFFFF);
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
        try {
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
        } finally {
            context.disableScissor();
        }
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
        float markerScale = PlanetEarthMinimapClient.config.selfMarkerSize / 9.0f;
        int arrowX = -client.textRenderer.getWidth(ARROW_GLYPH) / 2;
        int arrowY = -client.textRenderer.fontHeight / 2;
        PlatformCompat.push(context);
        PlatformCompat.translate(context, x, y);
        PlatformCompat.rotate(context, yaw + 180.0f);
        PlatformCompat.scale(context, markerScale, markerScale);
        context.drawText(client.textRenderer, ARROW_TEXT, arrowX - 1, arrowY, 0xFF000000, false);
        context.drawText(client.textRenderer, ARROW_TEXT, arrowX + 1, arrowY, 0xFF000000, false);
        context.drawText(client.textRenderer, ARROW_TEXT, arrowX, arrowY - 1, 0xFF000000, false);
        context.drawText(client.textRenderer, ARROW_TEXT, arrowX, arrowY + 1, 0xFF000000, false);
        context.drawText(client.textRenderer, ARROW_TEXT, arrowX, arrowY, 0xFFFFFFFF, false);
        PlatformCompat.pop(context);
    }

    private static Text coordinateText(double x, double y, double z) {
        long roundedX = Math.round(x);
        long roundedY = Math.round(y);
        long roundedZ = Math.round(z);
        if (roundedX != cachedCoordinateX || roundedY != cachedCoordinateY || roundedZ != cachedCoordinateZ) {
            cachedCoordinateX = roundedX;
            cachedCoordinateY = roundedY;
            cachedCoordinateZ = roundedZ;
            cachedCoordinateText = Text.literal(roundedX + ", " + roundedY + ", " + roundedZ);
        }
        return cachedCoordinateText;
    }

    private static final java.time.ZoneId KOREA_ZONE = java.time.ZoneId.of("Asia/Seoul");
    private static String cachedBiomeName = "";
    private static Object cachedBiomeWorld;
    private static long cachedBiomeBlockPos = Long.MIN_VALUE;
    private static long cachedStatusSecond = Long.MIN_VALUE;
    private static String cachedStatusText = "";
    private static Text cachedStatusTextComponent = Text.literal("");

    /** "평원 · 오후 3시 30분 45초" style small status text — biome name plus the real
     *  Korea-time clock (not the in-game day/night cycle). The biome half is cached and
     *  refreshed a few times a second at most, since a registry lookup + translation is
     *  comparatively expensive and the biome rarely changes frame to frame; the clock
     *  half is cheap to format and is recomputed every call so the seconds stay live. */
    private static String biomeAndClockText(MinecraftClient client) {
        refreshBiomeCache(client);
        long nowMillis = NetworkClock.currentTimeMillis();
        long second = nowMillis / 1_000L;
        if (second == cachedStatusSecond && !cachedStatusText.isEmpty()) return cachedStatusText;
        String clock = currentClockText(nowMillis);
        cachedStatusText = cachedBiomeName.isEmpty() ? clock : cachedBiomeName + " · " + clock;
        cachedStatusTextComponent = Text.literal(cachedStatusText);
        cachedStatusSecond = second;
        return cachedStatusText;
    }

    private static void refreshBiomeCache(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            cachedBiomeWorld = null;
            cachedBiomeBlockPos = Long.MIN_VALUE;
            cachedBiomeName = "";
            cachedStatusSecond = Long.MIN_VALUE;
            return;
        }
        long blockPos = client.player.getBlockPos().asLong();
        if (cachedBiomeWorld == client.world && cachedBiomeBlockPos == blockPos) return;
        RegistryEntry<Biome> biome = client.world.getBiome(client.player.getBlockPos());
        cachedBiomeWorld = client.world;
        cachedBiomeBlockPos = blockPos;
        cachedBiomeName = biome.getKey()
                .map(key -> Text.translatable("biome." + key.getValue().getNamespace()
                        + "." + key.getValue().getPath()).getString())
                .orElse("");
        cachedStatusSecond = Long.MIN_VALUE;
    }

    /** Real Korea-time (Asia/Seoul) clock as a 12-hour readout with seconds, e.g.
     *  "오후 3시 30분 45초" — independent of the player's system timezone and of the
     *  in-game day/night cycle. */
    private static String currentClockText(long nowMillis) {
        java.time.ZonedDateTime now = java.time.Instant.ofEpochMilli(nowMillis).atZone(KOREA_ZONE);
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
        int rawWidth = TextWidthCache.width(biomeAndClockText(client)) + 10;
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
            context.drawTextWithShadow(client.textRenderer, cachedStatusTextComponent,
                    x + 5, y + (height - client.textRenderer.fontHeight) / 2, 0xFFFFFFFF);
            return;
        }
        PlatformCompat.push(context);
        PlatformCompat.translate(context, Math.round(x + 5 * scale),
                Math.round(y + (height - client.textRenderer.fontHeight * scale) / 2f));
        PlatformCompat.scale(context, scale, scale);
        context.drawTextWithShadow(client.textRenderer, cachedStatusTextComponent, 0, 0, 0xFFFFFFFF);
        PlatformCompat.pop(context);
    }

    private static int cardinalDirectionIndex(float yaw) {
        return Math.floorMod(Math.round(yaw / 45.0f), DIRECTIONS.length);
    }

    /** Rotates a screen-space offset by the same angle {@link PlatformCompat#rotate}
     *  would apply to it through the matrix stack — used by every renderer (waypoints,
     *  site markers, players, navigation) that needs its icon to land in the correct
     *  spun position while drawing its own label upright afterwards, rather than
     *  drawing under an active rotated matrix the way the tile mosaic and grid lines
     *  do. Package-private so the other per-layer renderers can share one
     *  implementation instead of duplicating the trig, and so it lives once in the
     *  main source set instead of once per Minecraft-version compat layer. */
    static void rotateOffset(double dx, double dy, float rotationDegrees, double[] out) {
        if (rotationDegrees == 0f) {
            out[0] = dx;
            out[1] = dy;
            return;
        }
        // Called once per marker, territory label, player and waypoint every frame,
        // always with the same angle within a frame — so the trig is done once per
        // angle instead of once per item.
        if (rotationDegrees != rotateCacheDegrees) {
            double rad = Math.toRadians(rotationDegrees);
            rotateCacheCos = Math.cos(rad);
            rotateCacheSin = Math.sin(rad);
            rotateCacheDegrees = rotationDegrees;
        }
        double cos = rotateCacheCos;
        double sin = rotateCacheSin;
        out[0] = dx * cos - dy * sin;
        out[1] = dx * sin + dy * cos;
    }

    private static float rotateCacheDegrees = Float.NaN;
    private static double rotateCacheCos = 1.0;
    private static double rotateCacheSin = 0.0;

    /** Where a ray from the map's centre at the given bearing (0 = up/north, clockwise)
     *  meets the map's own edge — a circle's edge when circularShape is on, otherwise
     *  the square's actual border, so a rotating compass point (or, at bearing 0 with
     *  no rotation, the ordinary fixed "N") always sits right against the frame
     *  instead of floating short of a square's corners the way a fixed circular
     *  radius would. */
    private static void projectToMapEdge(int centerX, int centerY, int halfWidth, int halfHeight,
                                         boolean circular, double bearingDegrees, int inset, int[] out) {
        double rad = Math.toRadians(bearingDegrees);
        double dx = Math.sin(rad);
        double dy = -Math.cos(rad);
        if (circular) {
            int radius = Math.min(halfWidth, halfHeight) - inset;
            out[0] = centerX + (int) Math.round(dx * radius);
            out[1] = centerY + (int) Math.round(dy * radius);
            return;
        }
        double availableX = halfWidth - inset;
        double availableY = halfHeight - inset;
        double scale = Math.min(
                dx != 0 ? availableX / Math.abs(dx) : Double.MAX_VALUE,
                dy != 0 ? availableY / Math.abs(dy) : Double.MAX_VALUE);
        out[0] = centerX + (int) Math.round(dx * scale);
        out[1] = centerY + (int) Math.round(dy * scale);
    }
}
