package kr.planetearth.minimap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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

    private LiveAtlasPlayerManager() {}

    /** Returns a lightweight, immutable roster for the full-map player browser. */
    public static List<PlayerEntry> playerEntries() {
        refreshIfNeeded();
        List<WebPlayer> snapshot = players;
        List<PlayerEntry> entries = new ArrayList<>(snapshot.size());
        for (WebPlayer player : snapshot) {
            entries.add(new PlayerEntry(player.name, player.account, player.x, player.z));
        }
        return List.copyOf(entries);
    }

    public static int playerCount() {
        refreshIfNeeded();
        return players.size();
    }

    /** Changes only when players join, leave or rename; position updates stay allocation-free for the UI. */
    public static long rosterRevision() {
        refreshIfNeeded();
        return rosterRevision;
    }

    /** Resolves a search result again at click time so moving players use their latest coordinates. */
    public static PlayerEntry findPlayer(String account) {
        if (account == null) return null;
        for (WebPlayer player : players) {
            if (player.account.equalsIgnoreCase(account)) {
                return new PlayerEntry(player.name, player.account, player.x, player.z);
            }
        }
        return null;
    }

    static Identifier faceTexture(String account) {
        if (account == null || account.isBlank()) return null;
        String key = faceKey(account);
        Identifier texture = FACE_TEXTURES.get(key);
        if (texture == null) requestFace(account, key);
        return texture;
    }

    public static void render(DrawContext context, int mapX, int mapY, int width, int height,
                              double localX, double localZ, int zoom) {
        refreshIfNeeded();
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        double pixelsPerBlock = 4.0 / (1 << Math.max(0, Math.min(zoom, 7)));
        MinecraftClient client = MinecraftClient.getInstance();
        String localName = client.player == null ? "" : client.player.getName().getString();

        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        for (WebPlayer player : players) {
            if (player.name.equalsIgnoreCase(localName)) continue;
            int x = centerX + (int) Math.round((player.x - localX) * pixelsPerBlock);
            int y = centerY + (int) Math.round((player.z - localZ) * pixelsPerBlock);
            if (x < mapX + 7 || x >= mapX + width - 7 || y < mapY + 7 || y >= mapY + height - 7) continue;
            drawPlayer(context, player, x, y, mapX, mapX + width);
        }
        context.disableScissor();
    }

    private static void drawPlayer(DrawContext context, WebPlayer player, int x, int y,
                                   int mapLeft, int mapRight) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        final int headSize = config.showPlayerFaces ? config.playerFaceSize : 0;
        int headX = x - headSize / 2;
        int headY = y - headSize / 2;
        Identifier face = config.showPlayerFaces ? faceTexture(player.account) : null;

        if (config.showPlayerNames) {
            String label = player.name;
            float labelScale = config.playerNameScalePercent / 100.0f;
            int rawLabelWidth = client.textRenderer.getWidth(label);
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

        if (config.showPlayerFaces) {
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
        String base = PlanetEarthMinimapClient.config.mapUrl.replaceAll("/+$", "");
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
        nextRefresh = now + 500;
        String base = PlanetEarthMinimapClient.config.mapUrl.replaceAll("/+$", "");
        String url = base + "/up/world/world/" + now;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "PlanetEarthMinimap/0.1")
                .header("Referer", base + "/")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    List<WebPlayer> updated = new ArrayList<>();
                    for (JsonElement element : root.getAsJsonArray("players")) {
                        JsonObject object = element.getAsJsonObject();
                        if (!"world".equals(object.get("world").getAsString())) continue;
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
                    if (root.has("updates") && root.get("updates").isJsonArray()) {
                        for (JsonElement element : root.getAsJsonArray("updates")) {
                            if (!element.isJsonObject()) continue;
                            JsonObject update = element.getAsJsonObject();
                            if (!update.has("type") || !"tile".equals(update.get("type").getAsString())
                                    || !update.has("name")) continue;
                            long version = update.has("timestamp")
                                    ? update.get("timestamp").getAsLong()
                                    : System.currentTimeMillis();
                            LiveAtlasTileManager.onTileUpdate(update.get("name").getAsString(), version);
                        }
                    }
                })
                .exceptionally(error -> {
                    PlanetEarthMinimapClient.LOGGER.debug("LiveAtlas player update failed", error);
                    return null;
                })
                .whenComplete((unused, error) -> PENDING.set(false));
    }

    public record PlayerEntry(String name, String account, double x, double z) {}

    private record WebPlayer(String name, String account, double x, double z) {}
}
