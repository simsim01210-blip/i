package kr.planetearth.minimap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the same public player feed used by the PlanetEarth LiveAtlas website. */
public final class LiveAtlasPlayerManager {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final Map<String, Identifier> FACE_TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> FACE_PENDING = ConcurrentHashMap.newKeySet();
    private static volatile List<WebPlayer> players = List.of();
    private static volatile int rosterSignature;
    private static volatile long rosterRevision;
    private static volatile long nextRefresh;
    private static volatile boolean rosterLoaded;
    private static final double[] ROTATED_SCRATCH = new double[2];
    private static final java.util.regex.Pattern ACCOUNT_NAME =
            java.util.regex.Pattern.compile("[.*]?[A-Za-z0-9_]{2,16}");
    private static List<String> tabNames = List.of();
    private static int tabSignature;
    private static long tabRevision;
    private static long nextTabRefresh;
    private static int onlineCount;

    private LiveAtlasPlayerManager() {}

    /** Everyone connected to the server for the full-map player browser: players the
     *  web map currently shows first (clickable, {@code visible}), then everyone else
     *  from the in-game tab list — hidden on the map, in another world, vanished from
     *  Dynmap... — as name-only entries with no usable coordinates. */
    public static List<PlayerEntry> onlineEntries() {
        refreshIfNeeded();
        refreshTabListIfNeeded();
        List<WebPlayer> snapshot = shownPlayers();
        List<String> tab = tabNames;
        List<PlayerEntry> entries = new ArrayList<>(snapshot.size() + tab.size());
        Set<String> shown = new java.util.HashSet<>(snapshot.size() * 2);
        for (WebPlayer player : snapshot) {
            entries.add(new PlayerEntry(player.name, player.account, player.x, player.z, true));
            shown.add(player.account.toLowerCase(Locale.ROOT));
        }
        for (String name : tab) {
            if (shown.contains(name.toLowerCase(Locale.ROOT))) continue;
            entries.add(new PlayerEntry(name, name, 0, 0, false));
        }
        return List.copyOf(entries);
    }

    public static int onlineCount() {
        refreshIfNeeded();
        refreshTabListIfNeeded();
        return onlineCount;
    }

    /** Changes only when players join, leave, rename or appear/disappear on the web map;
     *  position updates stay allocation-free for the UI. */
    public static long rosterRevision() {
        refreshIfNeeded();
        refreshTabListIfNeeded();
        return (rosterRevision * 31 + tabRevision) * 2 + (WebMapHealth.isDown() ? 1 : 0);
    }

    /** Resolves a search result again at click time so moving players use their latest coordinates. */
    public static PlayerEntry findPlayer(String account) {
        if (account == null) return null;
        for (WebPlayer player : shownPlayers()) {
            if (player.account.equalsIgnoreCase(account)) {
                return new PlayerEntry(player.name, player.account, player.x, player.z, true);
            }
        }
        return null;
    }

    /** Whether the web map is currently showing the local player at all. Until the
     *  first feed for this session has arrived it's unknown, and treated as shown so
     *  the self marker doesn't flash grey on every join. */
    public static boolean isLocalPlayerOnMap() {
        if (!rosterLoaded) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return true;
        String account = client.player.getGameProfile().getName();
        for (WebPlayer player : shownPlayers()) {
            if (player.account.equalsIgnoreCase(account)) return true;
        }
        return false;
    }

    /** The web roster as it may be shown: nothing while the web map is down, since the
     *  last positions it gave are frozen and would show everyone standing still. */
    private static List<WebPlayer> shownPlayers() {
        return WebMapHealth.isDown() ? List.of() : players;
    }

    /** Server tab list, re-read at most twice a second (render thread only). NPC/fake
     *  rows that tab-layout plugins inject aren't real accounts, so only names that
     *  look like a Minecraft (or Floodgate-prefixed Bedrock) account are kept. */
    private static void refreshTabListIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextTabRefresh) return;
        nextTabRefresh = now + 500;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        List<String> names = new ArrayList<>();
        if (handler != null) {
            for (PlayerListEntry entry : handler.getPlayerList()) {
                if (entry == null || entry.getProfile() == null) continue;
                String name = entry.getProfile().getName();
                java.util.UUID id = entry.getProfile().getId();
                if (name == null || !ACCOUNT_NAME.matcher(name).matches()) continue;
                // Version 2 UUIDs are what Citizens and most fake-player plugins use.
                if (id != null && id.version() == 2) continue;
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        int signature = 1;
        for (String name : names) signature = 31 * signature + name.toLowerCase(Locale.ROOT).hashCode();
        if (signature != tabSignature || names.size() != tabNames.size()) {
            tabSignature = signature;
            tabRevision++;
        }
        tabNames = List.copyOf(names);
        // The web feed only ever lists players visible in *this* world; anyone else
        // online is still in the tab list, so the union is the real online count.
        Set<String> union = new java.util.HashSet<>();
        for (String name : names) union.add(name.toLowerCase(Locale.ROOT));
        for (WebPlayer player : shownPlayers()) union.add(player.account.toLowerCase(Locale.ROOT));
        onlineCount = union.size();
    }

    static Identifier faceTexture(String account) {
        if (account == null || account.isBlank()) return null;
        String key = faceKey(account);
        Identifier texture = FACE_TEXTURES.get(key);
        if (texture == null) requestFace(account, key);
        return texture;
    }

    public static void render(DrawContext context, int mapX, int mapY, int width, int height,
                              double localX, double localZ, int zoom, float rotationDegrees) {
        refreshIfNeeded();
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        double pixelsPerBlock = 4.0 / (1 << Math.max(0, Math.min(zoom, 7)));
        MinecraftClient client = MinecraftClient.getInstance();
        // Matched on the account, not the web map's display name — with a nickname
        // plugin the two differ and the local player used to be drawn twice.
        String localAccount = client.player == null ? "" : client.player.getGameProfile().getName();

        double[] rotated = ROTATED_SCRATCH;
        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            for (WebPlayer player : shownPlayers()) {
                if (player.account.equalsIgnoreCase(localAccount)) continue;
                // The dot's position rotates with the map, but drawPlayer draws the face
                // and nametag with no active rotation (only its own translate/scale), so
                // both stay upright instead of spinning or flipping as the map turns.
                MinimapHud.rotateOffset((player.x - localX) * pixelsPerBlock,
                        (player.z - localZ) * pixelsPerBlock, rotationDegrees, rotated);
                int x = centerX + (int) Math.round(rotated[0]);
                int y = centerY + (int) Math.round(rotated[1]);
                if (x < mapX + 7 || x >= mapX + width - 7 || y < mapY + 7 || y >= mapY + height - 7) continue;
                drawPlayer(context, player, x, y, mapX, mapX + width);
            }
        } finally {
            context.disableScissor();
        }
    }

    private static void drawPlayer(DrawContext context, WebPlayer player, int x, int y,
                                   int mapLeft, int mapRight) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        // Each visible face is its own network download, decode and texture upload —
        // 저사양 모드 skips them the same way it already skips territory colour and
        // rotation, falling back to the plain colour swatch below instead.
        boolean showFaces = config.showPlayerFaces && !config.lowSpecMode;
        final int headSize = showFaces ? config.playerFaceSize : 0;
        int headX = x - headSize / 2;
        int headY = y - headSize / 2;
        Identifier face = showFaces ? faceTexture(player.account) : null;

        if (config.showPlayerNames) {
            String label = player.name;
            float labelScale = config.playerNameScalePercent / 100.0f;
            int rawLabelWidth = TextWidthCache.width(label);
            int labelWidth = (int) Math.ceil(rawLabelWidth * labelScale);
            int labelHeight = (int) Math.ceil(client.textRenderer.fontHeight * labelScale);
            int labelX = x + headSize / 2 + 3;
            if (labelX + labelWidth + 2 > mapRight) labelX = x - headSize / 2 - labelWidth - 3;
            labelX = Math.max(mapLeft + 2, Math.min(labelX, mapRight - labelWidth - 2));
            int labelY = y - labelHeight / 2;

            PlatformCompat.push(context);
            PlatformCompat.translate(context, labelX, labelY);
            PlatformCompat.scale(context, labelScale, labelScale);
            context.fill(-2, -1, rawLabelWidth + 2,
                    client.textRenderer.fontHeight + 1, 0xB0000000);
            context.drawTextWithShadow(client.textRenderer, label, 0, 0, 0xFFFFFFFF);
            PlatformCompat.pop(context);
        }

        if (showFaces) {
            context.fill(headX - 1, headY - 1, headX + headSize + 1, headY + headSize + 1, 0xE0000000);
            if (face != null) {
                // Scale the complete 16x16 face. Passing headSize as the source size would crop it.
                float faceScale = headSize / 16.0f;
                PlatformCompat.push(context);
                PlatformCompat.translate(context, headX, headY);
                PlatformCompat.scale(context, faceScale, faceScale);
                PlatformCompat.drawTexture(context, face, 0, 0, 0, 0,
                        16, 16, 16, 16);
                PlatformCompat.pop(context);
            } else {
                context.fill(headX, headY, headX + headSize, headY + headSize, 0xFF55FFFF);
            }
        }
    }

    private static void requestFace(String account, String key) {
        if (account == null || account.isBlank() || !FACE_PENDING.add(key)) return;
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        String url = base + "/tiles/faces/16x16/" + account + ".png";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "PlanetEarthMinimap/0.1")
                .header("Referer", base + "/")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        FACE_PENDING.remove(key);
                        return;
                    }
                    try {
                        NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()));
                        try {
                            MinecraftClient.getInstance().execute(() -> uploadFace(key, image));
                        } catch (RuntimeException exception) {
                            image.close();
                            FACE_PENDING.remove(key);
                            throw exception;
                        }
                    } catch (Exception exception) {
                        FACE_PENDING.remove(key);
                        PlanetEarthMinimapClient.LOGGER.debug("Could not decode face for {}", account, exception);
                    }
                })
                .exceptionally(error -> {
                    FACE_PENDING.remove(key);
                    PlanetEarthMinimapClient.LOGGER.debug("Face download failed for {}", account, error);
                    return null;
                });
    }

    private static void uploadFace(String key, NativeImage image) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            // FACE_PENDING must stay set until this render-thread upload finishes.
            // Removing it when the HTTP request finishes allowed the render loop to
            // queue duplicate uploads. Because face paths are deterministic, the
            // later callback then destroyed the freshly registered texture and left
            // a missing magenta/black face behind.
            Identifier previous = FACE_TEXTURES.remove(key);
            if (previous != null) client.getTextureManager().destroyTexture(previous);
            Identifier texture = PlatformCompat.registerDynamicTexture(
                    client.getTextureManager(), "face/" + key, image);
            FACE_TEXTURES.put(key, texture);
        } catch (RuntimeException exception) {
            image.close();
            throw exception;
        } finally {
            FACE_PENDING.remove(key);
        }
    }

    private static String faceKey(String account) {
        return account.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh || !PENDING.compareAndSet(false, true)) return;
        // Every poll is a network round trip plus a full JSON re-parse of the whole
        // player roster — 저사양 모드 stretches the interval instead of cutting this
        // feed outright, since unlike faces/territory colour there's no per-item
        // toggle to fall back to; player dots just update a little less often.
        // While the web map is down this poll doubles as the recovery probe, so it
        // keeps going, just less often than a healthy server is polled.
        nextRefresh = now + (WebMapHealth.isDown() ? 2000
                : PlanetEarthMinimapClient.config != null
                && PlanetEarthMinimapClient.config.lowSpecMode ? 1500 : 500);
        // Follows whichever Dynmap world the player is actually standing in (world,
        // worldpvp, ...) instead of always polling "world" — otherwise the corner
        // minimap kept showing overworld players' dots while standing in World PvP,
        // and worldpvp players never showed up at all.
        String world = LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance());
        if (world == null) {
            // No map for this dimension at all (Nether, ...), so there is no player
            // feed for it either — clear the roster instead of leaving it showing
            // whichever world's players were last fetched, floating at their old,
            // now-meaningless coordinates on top of the loading indicator.
            if (!players.isEmpty()) {
                players = List.of();
                rosterRevision++;
            }
            rosterLoaded = true;
            PENDING.set(false);
            return;
        }
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        String url = base + "/up/world/" + world + "/" + now;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "PlanetEarthMinimap/0.1")
                .header("Referer", base + "/")
                .GET()
                .build();
        WebMapHealth.feedRequestStarted();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        WebMapHealth.recordFailure();
                        return;
                    }
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    List<WebPlayer> updated = new ArrayList<>();
                    for (JsonElement element : root.getAsJsonArray("players")) {
                        JsonObject object = element.getAsJsonObject();
                        if (!world.equals(object.get("world").getAsString())) continue;
                        updated.add(new WebPlayer(
                                object.get("name").getAsString(),
                                object.get("account").getAsString(),
                                object.get("x").getAsDouble(),
                                object.get("z").getAsDouble()
                        ));
                    }
                    updated.sort(Comparator.comparing(
                            player -> player.name.toLowerCase(Locale.ROOT)));
                    int updatedRosterSignature = 1;
                    for (WebPlayer player : updated) {
                        updatedRosterSignature = 31 * updatedRosterSignature
                                + player.name.toLowerCase(Locale.ROOT).hashCode();
                        updatedRosterSignature = 31 * updatedRosterSignature
                                + player.account.toLowerCase(Locale.ROOT).hashCode();
                    }
                    if (updatedRosterSignature != rosterSignature) {
                        rosterSignature = updatedRosterSignature;
                        rosterRevision++;
                    }
                    players = List.copyOf(updated);
                    rosterLoaded = true;
                    WebMapHealth.recordSuccess();
                    if (root.has("updates") && root.get("updates").isJsonArray()) {
                        for (JsonElement element : root.getAsJsonArray("updates")) {
                            if (!element.isJsonObject()) continue;
                            JsonObject update = element.getAsJsonObject();
                            if (!update.has("type") || !"tile".equals(update.get("type").getAsString())
                                    || !update.has("name")) continue;
                            long version = update.has("timestamp")
                                    ? update.get("timestamp").getAsLong()
                                    : System.currentTimeMillis();
                            LiveAtlasTileManager.onTileUpdate(world, update.get("name").getAsString(), version);
                        }
                    }
                })
                .exceptionally(error -> {
                    PlanetEarthMinimapClient.LOGGER.debug("LiveAtlas player update failed", error);
                    WebMapHealth.recordFailure();
                    return null;
                })
                .whenComplete((unused, error) -> {
                    WebMapHealth.feedRequestFinished();
                    PENDING.set(false);
                });
    }

    /** {@code visible} is false for players who are online but not on the web map;
     *  their x/z are meaningless then. */
    public record PlayerEntry(String name, String account, double x, double z, boolean visible) {}

    private record WebPlayer(String name, String account, double x, double z) {}
}
