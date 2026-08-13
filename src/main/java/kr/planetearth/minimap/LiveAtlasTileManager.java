package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/** Downloads and caches Dynmap/LiveAtlas flat-map tiles without blocking the render thread. */
public final class LiveAtlasTileManager {
    private static final int TILE_SIZE = 128;
    // A 1920x1080 full-map viewport alone can need around 150 tiles. The old
    // 96-tile limit evicted visible tiles before the viewport could finish loading.
    private static final int MAX_TILES = 768;
    private static final int EMPTY_TILE_MAX_BYTES = 256;
    private static final double MAP_SCALE = 4.0;
    private static final long FALLBACK_REFRESH_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long EMPTY_RETRY_NANOS = Duration.ofSeconds(5).toNanos();
    // Every one of these threads can be doing CPU-bound WebP/pixel decode work (see
    // decode() below) at the same time as the render thread, not just idle I/O waiting.
    // The old floor of 6 (up to 16) meant even a modest CPU had most of its cores busy
    // decoding tiles the moment a big viewport (e.g. the "다른 맵" overlay) needed many
    // at once, which showed up as game-wide stutter rather than a merely slow map.
    // Leaving a couple of cores free for the game itself trades a bit of initial-load
    // speed for not stealing frame time.
    private static final int NORMAL_POOL_SIZE =
            Math.max(3, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
    // 저사양 모드: leaves even more cores free for the game at the cost of slower tile
    // loading — see PlanetEarthMinimapClient.applyLowSpecMode.
    private static final int LOW_SPEC_POOL_SIZE = Math.max(1, NORMAL_POOL_SIZE / 2);
    private static final ThreadPoolExecutor TILE_EXECUTOR = (ThreadPoolExecutor)
            Executors.newFixedThreadPool(NORMAL_POOL_SIZE, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-tile-worker");
                thread.setDaemon(true);
                return thread;
            });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .executor(TILE_EXECUTOR)
            .build();
    private static final Map<TileKey, Tile> TILES = new ConcurrentHashMap<>();
    private static final Map<TileKey, CompletableFuture<?>> PENDING = new ConcurrentHashMap<>();
    private static final Map<TileKey, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final Map<TileKey, Long> UPDATE_VERSIONS = new ConcurrentHashMap<>();
    private static final Map<TileKey, PendingUpload> READY_UPLOADS = new ConcurrentHashMap<>();
    private static final Queue<TileKey> READY_UPLOAD_ORDER = new ConcurrentLinkedQueue<>();
    private static VisibleTileOrder recentTileOrder;
    private static VisibleTileOrder previousTileOrder;

    private LiveAtlasTileManager() {}

    /** Called on startup and whenever the "저사양 모드" toggle changes — resizes the
     *  tile worker pool in place, no restart needed. corePoolSize must never exceed
     *  maximumPoolSize even transiently, hence growing/shrinking in different orders. */
    public static void applyLowSpecMode(boolean lowSpec) {
        int size = lowSpec ? LOW_SPEC_POOL_SIZE : NORMAL_POOL_SIZE;
        if (size > TILE_EXECUTOR.getMaximumPoolSize()) {
            TILE_EXECUTOR.setMaximumPoolSize(size);
            TILE_EXECUTOR.setCorePoolSize(size);
        } else {
            TILE_EXECUTOR.setCorePoolSize(size);
            TILE_EXECUTOR.setMaximumPoolSize(size);
        }
    }

    public static boolean render(DrawContext context, int x, int y, int width, int height,
                                 double playerX, double playerZ) {
        return render(context, x, y, width, height, playerX, playerZ,
                PlanetEarthMinimapClient.config.zoom);
    }

    public static boolean render(DrawContext context, int x, int y, int width, int height,
                                 double playerX, double playerZ, int requestedZoom) {
        // The tile URL is always "/tiles/world/..." — there is only ever an overworld
        // map. In the Nether, X/Z are scaled 1:8 versus the overworld, so once the
        // player zoomed out far enough the requested tile keys would coincidentally
        // land on real (but totally unrelated) overworld tiles and the loading
        // indicator would wrongly disappear, showing that unrelated map as if this
        // world were supported. Treating every non-overworld dimension as having no
        // map at all avoids that regardless of zoom or what happens to be cached.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.world.getRegistryKey() != World.OVERWORLD) {
            return false;
        }
        int zoom = Math.max(0, Math.min(requestedZoom, 7));
        int zoomFactor = 1 << zoom;

        // This is Dynmap's flat-map projection as used by LiveAtlas. At native zoom
        // map X is 4*x and map Y is 128+4*z. Lower zoom levels divide both by 2^zoom.
        double centerMapX = playerX * MAP_SCALE / zoomFactor;
        double centerMapY = (TILE_SIZE + playerZ * MAP_SCALE) / zoomFactor;
        double left = centerMapX - width / 2.0;
        double top = centerMapY - height / 2.0;
        int firstTileX = floorDiv((int) Math.floor(left), TILE_SIZE);
        int lastTileX = floorDiv((int) Math.floor(left + width), TILE_SIZE);
        int firstTileY = floorDiv((int) Math.floor(top), TILE_SIZE);
        int lastTileY = floorDiv((int) Math.floor(top + height), TILE_SIZE);
        boolean drewAny = false;
        boolean missingAny = false;
        long now = System.nanoTime();

        // The same update request used for live player positions also contains the
        // exact tile paths changed by Dynmap. Poll it even when player markers are off.
        LiveAtlasPlayerManager.refreshIfNeeded();

        // Ask for the centre first so the useful part of the map appears before the
        // viewport edges. Row-major order made a large full map feel much slower.
        double centerTileX = centerMapX / TILE_SIZE;
        double centerTileY = centerMapY / TILE_SIZE;
        VisibleTileOrder tileOrder = visibleTileOrder(zoom,
                firstTileX, lastTileX, firstTileY, lastTileY, centerTileX, centerTileY);
        // Do not let a burst of completed HTTP requests upload every texture in one
        // client tick. Prioritize the current viewport within a small per-frame
        // budget, then use any spare slots for prefetched or previously visible tiles.
        uploadReadyTiles(tileOrder.keys, width >= 600 ? 6 : 3);

        context.enableScissor(x, y, x + width, y + height);
        for (TileKey key : tileOrder.keys) {
            Tile tile = TILES.get(key);
            if (tile == null) {
                missingAny = true;
                request(key, 0L);
                continue;
            }
            long updateVersion = UPDATE_VERSIONS.getOrDefault(key, 0L);
            if (tile.version < updateVersion) {
                request(key, updateVersion);
            } else if (now - tile.loadedAt > FALLBACK_REFRESH_NANOS) {
                request(key, System.currentTimeMillis());
            }
            tile.lastUsed = now;
            int drawX = x + (int) Math.floor(key.x * TILE_SIZE - left);
            int drawY = y + (int) Math.floor(key.y * TILE_SIZE - top);
            PlatformCompat.drawTexture(context, tile.textureId, drawX, drawY, 0, 0,
                    TILE_SIZE, TILE_SIZE, TILE_SIZE, TILE_SIZE);
            drewAny = true;
        }
        context.disableScissor();

        // Once the viewport is ready, fetch one surrounding ring. Small movements
        // then reveal already-cached tiles instead of showing another loading pause.
        // 저사양 모드 skips this entirely — it's a "nice to have" that trades some
        // bandwidth/CPU for smoother panning, exactly the kind of thing to cut first.
        if (!missingAny && !tileOrder.prefetched && !PlanetEarthMinimapClient.config.lowSpecMode) {
            prefetchBorder(zoom, firstTileX, lastTileX, firstTileY, lastTileY,
                    centerTileX, centerTileY);
            tileOrder.prefetched = true;
        }
        // Callers use this as the loading-state signal. A single cached tile is not
        // enough: keep the loading screen visible until the whole current viewport is
        // available, then reveal the completed map in one frame.
        return drewAny && !missingAny;
    }

    private static VisibleTileOrder visibleTileOrder(int zoom,
                                                     int firstTileX, int lastTileX,
                                                     int firstTileY, int lastTileY,
                                                     double centerTileX, double centerTileY) {
        if (recentTileOrder != null && recentTileOrder.matches(
                zoom, firstTileX, lastTileX, firstTileY, lastTileY)) {
            return recentTileOrder;
        }
        if (previousTileOrder != null && previousTileOrder.matches(
                zoom, firstTileX, lastTileX, firstTileY, lastTileY)) {
            VisibleTileOrder found = previousTileOrder;
            previousTileOrder = recentTileOrder;
            recentTileOrder = found;
            return found;
        }

        List<TileKey> keys = new ArrayList<>();
        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                keys.add(new TileKey(zoom, tileX, tileY));
            }
        }
        keys.sort(Comparator.comparingDouble(key ->
                square(key.x + 0.5 - centerTileX) + square(key.y + 0.5 - centerTileY)));
        VisibleTileOrder created = new VisibleTileOrder(zoom,
                firstTileX, lastTileX, firstTileY, lastTileY, List.copyOf(keys));
        previousTileOrder = recentTileOrder;
        recentTileOrder = created;
        return created;
    }

    private static void request(TileKey key, long requestedVersion) {
        long now = System.nanoTime();
        Long retryAfter = RETRY_AFTER.get(key);
        if (retryAfter != null && now < retryAfter) return;
        CompletableFuture<?> pending = PENDING.computeIfAbsent(key, ignored -> {
            long version = Math.max(requestedVersion, UPDATE_VERSIONS.getOrDefault(key, 0L));
            String base = PlanetEarthMinimapClient.config.mapUrl.replaceAll("/+$", "");
            String url = base + "/tiles/world/" + relativePath(key)
                    + (version > 0 ? "?timestamp=" + version : "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "PlanetEarthMinimap/0.1")
                    .header("Referer", base + "/")
                    .GET()
                    .build();
            CompletableFuture<Void> lifecycle = new CompletableFuture<>();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200
                                || response.body().length <= EMPTY_TILE_MAX_BYTES) {
                            // LiveAtlas answers with HTTP 200 and a ~116-byte solid-blue
                            // WebP for tiles that have not been rendered. Treat it as
                            // missing instead of a successfully loaded map tile.
                            RETRY_AFTER.put(key, System.nanoTime() + EMPTY_RETRY_NANOS);
                            return null;
                        }
                        return decode(response.body());
                    })
                    .thenAccept(image -> {
                        if (image == null) {
                            lifecycle.complete(null);
                            return;
                        }
                        READY_UPLOADS.put(key, new PendingUpload(key, image, version, lifecycle));
                        READY_UPLOAD_ORDER.add(key);
                    })
                    .exceptionally(error -> {
                        PlanetEarthMinimapClient.LOGGER.debug("Tile download failed for {}", key, error);
                        lifecycle.completeExceptionally(error);
                        return null;
                    });
            return lifecycle;
        });
        // Keep the key pending until the texture has actually been uploaded on the
        // render thread. HTTP completion alone is too early and causes duplicate
        // downloads/uploads while the render task is still queued.
        pending.whenComplete((unused, error) -> PENDING.remove(key, pending));
    }

    private static void uploadReadyTiles(List<TileKey> visibleKeys, int budget) {
        int uploaded = 0;
        for (TileKey key : visibleKeys) {
            if (uploaded >= budget) return;
            PendingUpload ready = READY_UPLOADS.remove(key);
            if (ready == null) continue;
            finishUpload(ready);
            uploaded++;
        }
        while (uploaded < budget) {
            TileKey key = READY_UPLOAD_ORDER.poll();
            if (key == null) return;
            PendingUpload ready = READY_UPLOADS.remove(key);
            if (ready == null) continue;
            finishUpload(ready);
            uploaded++;
        }
    }

    private static void finishUpload(PendingUpload ready) {
        try {
            upload(ready.key, ready.image, ready.version);
            ready.lifecycle.complete(null);
        } catch (Throwable error) {
            ready.image.close();
            ready.lifecycle.completeExceptionally(error);
        }
    }

    /** Called with the tile update records returned by Dynmap's live update feed. */
    static void onTileUpdate(String path, long version) {
        if (path == null || !path.startsWith("flat/")) return;
        String normalized = path.replace('\\', '/');
        for (TileKey key : PENDING.keySet()) {
            if (relativePath(key).equals(normalized)) {
                UPDATE_VERSIONS.merge(key, version, Math::max);
            }
        }
        for (TileKey key : TILES.keySet()) {
            if (!relativePath(key).equals(normalized)) continue;
            UPDATE_VERSIONS.merge(key, version, Math::max);
            request(key, version);
        }
    }

    private static void prefetchBorder(int zoom, int firstX, int lastX, int firstY, int lastY,
                                       double centerX, double centerY) {
        List<TileKey> border = new ArrayList<>();
        for (int x = firstX - 1; x <= lastX + 1; x++) {
            border.add(new TileKey(zoom, x, firstY - 1));
            border.add(new TileKey(zoom, x, lastY + 1));
        }
        for (int y = firstY; y <= lastY; y++) {
            border.add(new TileKey(zoom, firstX - 1, y));
            border.add(new TileKey(zoom, lastX + 1, y));
        }
        border.sort(Comparator.comparingDouble(key ->
                square(key.x + 0.5 - centerX) + square(key.y + 0.5 - centerY)));
        for (TileKey key : border) {
            if (!TILES.containsKey(key)) request(key, 0L);
        }
    }

    private static String relativePath(TileKey key) {
        int zoomFactor = 1 << key.zoom;
        int fileX = zoomFactor * key.x;
        int fileY = -(zoomFactor * key.y);
        int groupX = fileX >> 5;
        int groupY = fileY >> 5;
        String zoomPrefix = "z".repeat(key.zoom) + (key.zoom == 0 ? "" : "_");
        return "flat/" + groupX + "_" + groupY + "/"
                + zoomPrefix + fileX + "_" + fileY + ".webp";
    }

    private static double square(double value) {
        return value * value;
    }

    private static NativeImage decode(byte[] bytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null) return null;
            int width = source.getWidth();
            int height = source.getHeight();
            // One bulk raster read instead of width*height individual getRGB(x, y) calls.
            // Each call re-runs the image's ColorModel conversion from scratch, and with
            // up to a dozen tile-worker threads doing this at once for a big viewport, the
            // per-pixel version was heavy enough to steal noticeable CPU from the render
            // thread and show up as stutter, not just a slow background download.
            int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
            NativeImage image = new NativeImage(width, height, true);
            for (int py = 0; py < height; py++) {
                int rowStart = py * width;
                for (int px = 0; px < width; px++) {
                    PlatformCompat.setNativeImageColor(image, px, py, pixels[rowStart + px]);
                }
            }
            return image;
        } catch (Exception exception) {
            PlanetEarthMinimapClient.LOGGER.debug("Could not decode LiveAtlas tile", exception);
            return null;
        }
    }

    private static void upload(TileKey key, NativeImage image, long version) {
        MinecraftClient client = MinecraftClient.getInstance();
        RETRY_AFTER.remove(key);

        // Dynamic tile paths are deterministic. Registering a refreshed image therefore
        // replaces the texture under the same Identifier. Destroy the old registration
        // first; destroying it after registerDynamicTexture() would delete the freshly
        // uploaded texture and leave the cache pointing at Minecraft's missing texture.
        Tile previous = TILES.remove(key);
        if (previous != null) {
            client.getTextureManager().destroyTexture(previous.textureId);
        }
        Identifier id = PlatformCompat.registerDynamicTexture(client.getTextureManager(),
                "tile/" + key.zoom + "_" + key.x + "_" + key.y, image);
        TILES.put(key, new Tile(id, version));
        trimCache(client);
    }

    private static void trimCache(MinecraftClient client) {
        while (TILES.size() > MAX_TILES) {
            Iterator<Map.Entry<TileKey, Tile>> iterator = TILES.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<TileKey, Tile> oldest = iterator.next();
            for (Map.Entry<TileKey, Tile> entry : TILES.entrySet()) {
                if (entry.getValue().lastUsed < oldest.getValue().lastUsed) oldest = entry;
            }
            if (TILES.remove(oldest.getKey(), oldest.getValue())) {
                client.getTextureManager().destroyTexture(oldest.getValue().textureId);
            }
        }
    }

    private static int floorDiv(int value, int divisor) {
        return Math.floorDiv(value, divisor);
    }

    private record TileKey(int zoom, int x, int y) {}

    private record PendingUpload(TileKey key, NativeImage image, long version,
                                 CompletableFuture<Void> lifecycle) {}

    private static final class VisibleTileOrder {
        private final int zoom;
        private final int firstX;
        private final int lastX;
        private final int firstY;
        private final int lastY;
        private final List<TileKey> keys;
        private boolean prefetched;

        private VisibleTileOrder(int zoom, int firstX, int lastX,
                                 int firstY, int lastY, List<TileKey> keys) {
            this.zoom = zoom;
            this.firstX = firstX;
            this.lastX = lastX;
            this.firstY = firstY;
            this.lastY = lastY;
            this.keys = keys;
        }

        private boolean matches(int zoom, int firstX, int lastX, int firstY, int lastY) {
            return this.zoom == zoom && this.firstX == firstX && this.lastX == lastX
                    && this.firstY == firstY && this.lastY == lastY;
        }
    }

    private static final class Tile {
        private final Identifier textureId;
        private final long version;
        private final long loadedAt = System.nanoTime();
        private volatile long lastUsed = System.nanoTime();

        private Tile(Identifier textureId, long version) {
            this.textureId = textureId;
            this.version = version;
        }
    }
}
