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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/** Downloads and caches Dynmap/LiveAtlas flat-map tiles without blocking the render thread. */
public final class LiveAtlasTileManager {
    private static final int TILE_SIZE = 128;
    // A 1920x1080 full-map viewport alone can need around 150 tiles. The old
    // 96-tile limit evicted visible tiles before the viewport could finish loading.
    private static final int NORMAL_MAX_TILES = 768;
    private static final int LOW_SPEC_MAX_TILES = 384;
    // A full-screen viewport can ask for well over a hundred tiles in one frame.
    // Keep enough requests in flight to saturate the workers without retaining an
    // unbounded number of response byte arrays and decoded NativeImages.
    private static final int NORMAL_MAX_PENDING = 96;
    private static final int LOW_SPEC_MAX_PENDING = 32;
    private static final int PRIORITY_EXTRA_PENDING = 64;
    private static final int EMPTY_TILE_MAX_BYTES = 256;
    private static final double MAP_SCALE = 4.0;
    private static final long FALLBACK_REFRESH_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long EMPTY_RETRY_NANOS = Duration.ofSeconds(5).toNanos();
    // Open ocean far from anywhere a player has actually sailed is often genuinely
    // never rendered server-side at all (LiveAtlas only draws chunks someone has
    // loaded), not just "still rendering" — retrying that at a flat 5 seconds forever
    // meant the loading indicator kept popping back on indefinitely every time a tile
    // like that re-entered view. Backing off exponentially per tile (still capped, and
    // still reset the moment a real image ever does arrive) turns that into an
    // occasional retry instead of a repeating flicker, without ever fully giving up in
    // case the area gets explored and rendered later.
    private static final long EMPTY_RETRY_MAX_NANOS = Duration.ofMinutes(3).toNanos();
    private static final Map<TileKey, Integer> EMPTY_STREAK = new ConcurrentHashMap<>();
    // After this many empty responses in a row for the same tile, stop counting it as
    // "still loading" for the purposes of the loading indicator — one confirmed-absent
    // tile at the edge of an otherwise fully loaded view (the common shape of this:
    // sailing along a coastline, where most of the view is real rendered map and only
    // the open-ocean edge is unrendered) used to keep the whole map's loading overlay
    // up indefinitely, hiding the perfectly good tiles under it too.
    private static final int CONFIRMED_EMPTY_STREAK = 2;
    // Every one of these threads can be doing CPU-bound WebP/pixel decode work (see
    // decode() below) at the same time as the render thread, not just idle I/O waiting.
    // The old floor of 6 (up to 16) meant even a modest CPU had most of its cores busy
    // decoding tiles the moment a big viewport (e.g. the "다른 맵" overlay) needed many
    // at once, which showed up as game-wide stutter rather than a merely slow map.
    // Leaving a couple of cores free for the game itself trades a bit of initial-load
    // speed for not stealing frame time.
    private static final int NORMAL_POOL_SIZE =
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
    // 저사양 모드: leaves even more cores free for the game at the cost of slower tile
    // loading — see PlanetEarthMinimapClient.applyLowSpecMode.
    private static final int LOW_SPEC_POOL_SIZE = Math.max(1, NORMAL_POOL_SIZE / 2);
    private static final int PRIORITY_POOL_SIZE = 2;
    private static final int LOW_SPEC_PRIORITY_POOL_SIZE = 1;
    private static final long PRIORITY_VIEW_HOLD_NANOS = Duration.ofMillis(250).toNanos();
    private static final ThreadPoolExecutor TILE_EXECUTOR = (ThreadPoolExecutor)
            Executors.newFixedThreadPool(NORMAL_POOL_SIZE, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-tile-worker");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
    // The hold-G auxiliary map is explicitly user-requested content and must not wait
    // behind WebP decode jobs queued by the small minimap or background prefetching.
    private static final ThreadPoolExecutor PRIORITY_TILE_EXECUTOR = (ThreadPoolExecutor)
            Executors.newFixedThreadPool(PRIORITY_POOL_SIZE, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-overlay-tile-worker");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            });
    // HttpClient bookkeeping must stay responsive even while every tile worker is
    // busy decoding WebP pixels. These threads spend almost all of their time waiting
    // for the network and do not increase the number of simultaneous CPU decodes.
    private static final ExecutorService HTTP_EXECUTOR =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-tile-http");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
    private static final ExecutorService PRIORITY_HTTP_EXECUTOR =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-overlay-tile-http");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .executor(HTTP_EXECUTOR)
            .build();
    private static final HttpClient PRIORITY_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .executor(PRIORITY_HTTP_EXECUTOR)
            .build();
    private static final Map<TileKey, Tile> TILES = new ConcurrentHashMap<>();
    private static final Map<TileKey, CompletableFuture<?>> PENDING = new ConcurrentHashMap<>();
    private static final Map<TileKey, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final java.util.Set<TileKey> FAILED = ConcurrentHashMap.newKeySet();
    private static final long FAILED_RETRY_NANOS = Duration.ofSeconds(3).toNanos();
    private static final Map<TileKey, Long> UPDATE_VERSIONS = new ConcurrentHashMap<>();
    private static final Map<TileKey, PendingUpload> READY_UPLOADS = new ConcurrentHashMap<>();
    private static final Map<String, TileKey> ACTIVE_BY_PATH = new ConcurrentHashMap<>();
    private static final Queue<TileKey> READY_UPLOAD_ORDER = new ConcurrentLinkedQueue<>();
    private static VisibleTileOrder recentTileOrder;
    private static VisibleTileOrder previousTileOrder;
    private static long nextMetadataCleanup;
    private static volatile long priorityViewUntilNanos;

    private LiveAtlasTileManager() {}

    /** Called on startup and whenever the "저사양 모드" toggle changes — resizes the
     *  tile worker pool in place, no restart needed. corePoolSize must never exceed
     *  maximumPoolSize even transiently, hence growing/shrinking in different orders. */
    public static void applyLowSpecMode(boolean lowSpec) {
        int size = lowSpec ? LOW_SPEC_POOL_SIZE : NORMAL_POOL_SIZE;
        resizeExecutor(TILE_EXECUTOR, size);
        resizeExecutor(PRIORITY_TILE_EXECUTOR,
                lowSpec ? LOW_SPEC_PRIORITY_POOL_SIZE : PRIORITY_POOL_SIZE);
        trimCache(MinecraftClient.getInstance());
    }

    private static void resizeExecutor(ThreadPoolExecutor executor, int size) {
        if (size > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(size);
            executor.setCorePoolSize(size);
        } else {
            executor.setCorePoolSize(size);
            executor.setMaximumPoolSize(size);
        }
    }

    /** Which Dynmap "world" the player's current Minecraft dimension corresponds to,
     *  or null if this server doesn't publish a map for it at all. Matched by registry
     *  key path only, not the full namespaced identifier — Paper commonly registers
     *  non-vanilla worlds like this one under a namespace that isn't "minecraft", and
     *  the path alone ("worldpvp") is what actually identifies it either way. Shared
     *  with LiveAtlasPlayerManager so the player list stays consistent with whichever
     *  map is actually on screen. */
    static String currentDynmapWorld(MinecraftClient client) {
        if (client.world == null) return null;
        Identifier id = client.world.getRegistryKey().getValue();
        if (id.equals(World.OVERWORLD.getValue())) return "world";
        if ("worldpvp".equals(id.getPath())) return "worldpvp";
        if ("moon".equals(id.getPath())) return "moon";
        return null;
    }

    public static boolean render(DrawContext context, int x, int y, int width, int height,
                                 double playerX, double playerZ) {
        return render(context, x, y, width, height, playerX, playerZ,
                PlanetEarthMinimapClient.config.zoom);
    }

    public static boolean render(DrawContext context, int x, int y, int width, int height,
                                 double playerX, double playerZ, int requestedZoom) {
        return render(context, x, y, width, height, playerX, playerZ, requestedZoom, false);
    }

    public static boolean render(DrawContext context, int x, int y, int width, int height,
                                 double playerX, double playerZ, int requestedZoom,
                                 boolean highPriority) {
        // Every dimension's tiles now live under their own "/tiles/<world>/..." path
        // (see currentDynmapWorld), instead of every non-overworld dimension being
        // treated as unsupported. That blanket rule used to also cover World PvP,
        // which the server actually does publish a real map for — it was only ever
        // needed for the Nether, where X/Z are scaled 1:8 versus the overworld, so
        // zooming out far enough used to make requested tile keys coincidentally land
        // on real (but totally unrelated) overworld tiles.
        MinecraftClient client = MinecraftClient.getInstance();
        String world = currentDynmapWorld(client);
        if (world == null) return false;
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
        boolean missingExactTile = false;
        long now = System.nanoTime();
        if (highPriority) priorityViewUntilNanos = now + PRIORITY_VIEW_HOLD_NANOS;
        cleanupMetadata(now);

        // The same update request used for live player positions also contains the
        // exact tile paths changed by Dynmap. Poll it even when player markers are off.
        LiveAtlasPlayerManager.refreshIfNeeded();

        // Ask for the centre first so the useful part of the map appears before the
        // viewport edges. Row-major order made a large full map feel much slower.
        double centerTileX = centerMapX / TILE_SIZE;
        double centerTileY = centerMapY / TILE_SIZE;
        VisibleTileOrder tileOrder = visibleTileOrder(world, zoom,
                firstTileX, lastTileX, firstTileY, lastTileY, centerTileX, centerTileY);
        // Do not let a burst of completed HTTP requests upload every texture in one
        // client tick. Prioritize the current viewport within a small per-frame
        // budget, then use any spare slots for prefetched or previously visible tiles.
        uploadReadyTiles(tileOrder.keys,
                highPriority ? 8 : width >= 600 ? 6 : 3,
                highPriority ? 4_000_000L : width >= 600 ? 3_000_000L : 1_500_000L);

        // The scissor stack lives on the DrawContext, which every other mod's HUD/screen
        // drawing in the same frame shares — so it always has to be popped again, even
        // if something below throws, or the leftover clip rectangle silently cuts off
        // whatever draws after this mod.
        context.enableScissor(x, y, x + width, y + height);
        try {
            for (TileKey key : tileOrder.keys) {
                int drawX = x + (int) Math.floor(key.x * TILE_SIZE - left);
                int drawY = y + (int) Math.floor(key.y * TILE_SIZE - top);
                Tile tile = TILES.get(key);
                if (tile == null) {
                    missingExactTile = true;
                    request(key, 0L, highPriority);
                    // Zoom levels use separate LiveAtlas images. Reuse an already cached
                    // neighbouring level until the requested image arrives, rather than
                    // replacing an otherwise usable map with the loading screen after each
                    // mouse-wheel step. The exact-resolution request above still continues
                    // in the background and replaces this temporary fallback automatically.
                    boolean drewFallback = drawZoomFallback(context, key, drawX, drawY, now);
                    drewAny |= drewFallback;
                    // A tile that has come back empty/unrendered several times running is
                    // very likely genuinely absent server-side (unexplored ocean LiveAtlas
                    // has no chunk data for), not merely slow to arrive — past that point,
                    // don't let it hold the whole map's loading indicator up forever.
                    boolean confirmedAbsent = EMPTY_STREAK.getOrDefault(key, 0) >= CONFIRMED_EMPTY_STREAK;
                    if (!confirmedAbsent) missingAny |= !drewFallback;
                    continue;
                }
                long updateVersion = UPDATE_VERSIONS.getOrDefault(key, 0L);
                if (tile.version < updateVersion) {
                    request(key, updateVersion, highPriority);
                } else if (now - tile.loadedAt > FALLBACK_REFRESH_NANOS) {
                    request(key, System.currentTimeMillis(), highPriority);
                }
                tile.lastUsed = now;
                PlatformCompat.drawTexture(context, tile.textureId, drawX, drawY, 0, 0,
                        TILE_SIZE, TILE_SIZE, TILE_SIZE, TILE_SIZE);
                drewAny = true;
            }
        } finally {
            context.disableScissor();
        }

        // Once the viewport is ready, fetch one surrounding ring. Small movements
        // then reveal already-cached tiles instead of showing another loading pause.
        // 저사양 모드 skips this entirely — it's a "nice to have" that trades some
        // bandwidth/CPU for smoother panning, exactly the kind of thing to cut first.
        if (!missingExactTile && !tileOrder.prefetched
                && !PlanetEarthMinimapClient.config.lowSpecMode) {
            // The next zoom-out shows a wider world area that the current fine tiles
            // cannot cover. Warm that one level first so both scroll directions feel
            // immediate; zooming in can already reuse the current coarser tiles.
            prefetchNextZoomOut(world, zoom, playerX, playerZ, width, height, highPriority);
            prefetchBorder(world, zoom, firstTileX, lastTileX, firstTileY, lastTileY,
                    centerTileX, centerTileY, highPriority);
            tileOrder.prefetched = true;
        }
        // Callers use this as the loading-state signal. A single cached tile is not
        // enough: keep the loading screen visible until the whole current viewport is
        // available, then reveal the completed map in one frame.
        return drewAny && !missingAny;
    }

    private static VisibleTileOrder visibleTileOrder(String world, int zoom,
                                                     int firstTileX, int lastTileX,
                                                     int firstTileY, int lastTileY,
                                                     double centerTileX, double centerTileY) {
        if (recentTileOrder != null && recentTileOrder.matches(
                world, zoom, firstTileX, lastTileX, firstTileY, lastTileY)) {
            return recentTileOrder;
        }
        if (previousTileOrder != null && previousTileOrder.matches(
                world, zoom, firstTileX, lastTileX, firstTileY, lastTileY)) {
            VisibleTileOrder found = previousTileOrder;
            previousTileOrder = recentTileOrder;
            recentTileOrder = found;
            return found;
        }

        List<TileKey> keys = new ArrayList<>();
        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                keys.add(new TileKey(world, zoom, tileX, tileY));
            }
        }
        keys.sort(Comparator.comparingDouble(key ->
                square(key.x + 0.5 - centerTileX) + square(key.y + 0.5 - centerTileY)));
        VisibleTileOrder created = new VisibleTileOrder(world, zoom,
                firstTileX, lastTileX, firstTileY, lastTileY, List.copyOf(keys));
        previousTileOrder = recentTileOrder;
        recentTileOrder = created;
        return created;
    }

    private static void request(TileKey key, long requestedVersion) {
        request(key, requestedVersion, false);
    }

    private static void request(TileKey key, long requestedVersion, boolean highPriority) {
        long now = System.nanoTime();
        // While the auxiliary map is visible, its centre-first visible requests own the
        // pipeline. Background border/next-zoom prefetch resumes a fraction of a second
        // after the map closes, without needing any explicit close callback.
        if (!highPriority && now < priorityViewUntilNanos) return;
        Long retryAfter = RETRY_AFTER.get(key);
        if (retryAfter != null && now < retryAfter) return;
        // Returning here is also important for allocation pressure: the old code
        // attached another whenComplete callback every frame while the same request
        // was pending. A large full-map viewport could therefore build thousands of
        // duplicate callbacks before a slow tile finished.
        if (PENDING.containsKey(key)) return;
        // Web map not answering: don't queue up a full viewport of requests against
        // it, just an occasional probe so recovery is noticed (see WebMapHealth).
        if (WebMapHealth.isDown() && !WebMapHealth.allowProbe()) return;
        int pendingLimit = maxPendingRequests()
                + (highPriority ? PRIORITY_EXTRA_PENDING : 0);
        if (PENDING.size() >= pendingLimit) return;

        CompletableFuture<Void> lifecycle = new CompletableFuture<>();
        if (PENDING.putIfAbsent(key, lifecycle) != null) return;
        String path = relativePath(key);
        String activeKey = activeKey(key);
        ACTIVE_BY_PATH.put(activeKey, key);
        lifecycle.whenComplete((unused, error) -> {
            PENDING.remove(key, lifecycle);
            if (!TILES.containsKey(key) && !READY_UPLOADS.containsKey(key)) {
                ACTIVE_BY_PATH.remove(activeKey, key);
            }
        });
        try {
            long version = Math.max(requestedVersion, UPDATE_VERSIONS.getOrDefault(key, 0L));
            String base = PlanetEarthMinimapClient.config.mapBaseUrl();
            String url = base + "/tiles/" + key.world + "/" + path
                    + (version > 0 ? "?timestamp=" + version : "");
            WebMapFetcher.fetch(highPriority ? PRIORITY_HTTP : HTTP, url, Duration.ofSeconds(12), null)
                    .thenApplyAsync(response -> {
                        int status = response.status();
                        if (status != 200 && status != 404) {
                            // 5xx / 429 / Cloudflare challenge...: the server is having
                            // trouble, which says nothing about whether this tile exists.
                            // Counting it as an empty tile used to push perfectly good
                            // tiles into the multi-minute empty-tile backoff, so the map
                            // stayed blank long after the web map itself came back.
                            markFailed(key);
                            return null;
                        }
                        if (status == 404 || response.body().length <= EMPTY_TILE_MAX_BYTES) {
                            WebMapHealth.recordSuccess();
                            // LiveAtlas answers with HTTP 200 and a ~116-byte solid-blue
                            // WebP for tiles that have not been rendered. Treat it as
                            // missing instead of a successfully loaded map tile.
                            int streak = EMPTY_STREAK.merge(key, 1, Integer::sum);
                            long delay = (long) Math.min(
                                    EMPTY_RETRY_NANOS * (1L << Math.min(streak - 1, 20)),
                                    EMPTY_RETRY_MAX_NANOS);
                            RETRY_AFTER.put(key, System.nanoTime() + delay);
                            return null;
                        }
                        NativeImage decoded = decode(response.body());
                        // A 200 that isn't an image is an error page served by a proxy
                        // in front of the map, not a real tile.
                        if (decoded == null) markFailed(key);
                        else WebMapHealth.recordSuccess();
                        return decoded;
                    }, highPriority ? PRIORITY_TILE_EXECUTOR : TILE_EXECUTOR)
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
                        markFailed(key);
                        lifecycle.completeExceptionally(error);
                        return null;
                    });
        } catch (Throwable error) {
            lifecycle.completeExceptionally(error);
        }
    }

    /** A download that failed because of the server/network rather than the tile:
     *  short retry, and remembered so it can be retried at once on recovery. */
    private static void markFailed(TileKey key) {
        WebMapHealth.recordFailure();
        RETRY_AFTER.put(key, System.nanoTime() + FAILED_RETRY_NANOS);
        FAILED.add(key);
    }

    static void onWebMapRecovered() {
        for (TileKey key : FAILED) RETRY_AFTER.remove(key);
        FAILED.clear();
    }

    private static void uploadReadyTiles(List<TileKey> visibleKeys, int budget, long maxNanos) {
        int uploaded = 0;
        long deadline = System.nanoTime() + maxNanos;
        for (TileKey key : visibleKeys) {
            if (uploaded >= budget || (uploaded > 0 && System.nanoTime() >= deadline)) return;
            PendingUpload ready = READY_UPLOADS.remove(key);
            if (ready == null) continue;
            finishUpload(ready);
            uploaded++;
        }
        while (uploaded < budget && (uploaded == 0 || System.nanoTime() < deadline)) {
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

    /** Called with the tile update records returned by Dynmap's live update feed for
     *  the given world. */
    static void onTileUpdate(String world, String path, long version) {
        if (path == null) return;
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("flat/")) return;
        TileKey key = ACTIVE_BY_PATH.get(world + "/" + normalized);
        if (key == null) return;
        UPDATE_VERSIONS.merge(key, version, Math::max);
        if (TILES.containsKey(key)) request(key, version);
    }

    private static void prefetchBorder(String world, int zoom, int firstX, int lastX,
                                       int firstY, int lastY, double centerX, double centerY,
                                       boolean highPriority) {
        List<TileKey> border = new ArrayList<>();
        for (int x = firstX - 1; x <= lastX + 1; x++) {
            border.add(new TileKey(world, zoom, x, firstY - 1));
            border.add(new TileKey(world, zoom, x, lastY + 1));
        }
        for (int y = firstY; y <= lastY; y++) {
            border.add(new TileKey(world, zoom, firstX - 1, y));
            border.add(new TileKey(world, zoom, lastX + 1, y));
        }
        border.sort(Comparator.comparingDouble(key ->
                square(key.x + 0.5 - centerX) + square(key.y + 0.5 - centerY)));
        for (TileKey key : border) {
            if (!TILES.containsKey(key)) request(key, 0L, highPriority);
        }
    }

    private static void prefetchNextZoomOut(String world, int zoom,
                                            double playerX, double playerZ,
                                            int width, int height,
                                            boolean highPriority) {
        if (zoom >= 7) return;
        int nextZoom = zoom + 1;
        int zoomFactor = 1 << nextZoom;
        double centerMapX = playerX * MAP_SCALE / zoomFactor;
        double centerMapY = (TILE_SIZE + playerZ * MAP_SCALE) / zoomFactor;
        double left = centerMapX - width / 2.0;
        double top = centerMapY - height / 2.0;
        int firstX = floorDiv((int) Math.floor(left), TILE_SIZE);
        int lastX = floorDiv((int) Math.floor(left + width), TILE_SIZE);
        int firstY = floorDiv((int) Math.floor(top), TILE_SIZE);
        int lastY = floorDiv((int) Math.floor(top + height), TILE_SIZE);
        double centerX = centerMapX / TILE_SIZE;
        double centerY = centerMapY / TILE_SIZE;

        List<TileKey> keys = new ArrayList<>((lastX - firstX + 1) * (lastY - firstY + 1));
        for (int tileY = firstY; tileY <= lastY; tileY++) {
            for (int tileX = firstX; tileX <= lastX; tileX++) {
                TileKey key = new TileKey(world, nextZoom, tileX, tileY);
                if (!TILES.containsKey(key)) keys.add(key);
            }
        }
        keys.sort(Comparator.comparingDouble(key ->
                square(key.x + 0.5 - centerX) + square(key.y + 0.5 - centerY)));
        for (TileKey key : keys) request(key, 0L, highPriority);
    }

    /**
     * Covers one missing tile from a cached neighbouring zoom. A coarser parent is a
     * single cropped texture; a finer level is usable only when all four children are
     * cached so the tile is never left partially blank. Returns true only for complete
     * coverage, which lets callers suppress the loading overlay safely.
     */
    private static boolean drawZoomFallback(DrawContext context, TileKey key,
                                            int drawX, int drawY, long now) {
        // Prefer the closest cached coarser level. Three levels still leave a useful
        // 16x16 source region while covering fast multi-notch wheel input.
        int maximumAncestor = Math.min(7, key.zoom + 3);
        for (int ancestorZoom = key.zoom + 1;
             ancestorZoom <= maximumAncestor; ancestorZoom++) {
            int factor = 1 << (ancestorZoom - key.zoom);
            TileKey parentKey = new TileKey(key.world, ancestorZoom,
                    floorDiv(key.x, factor), floorDiv(key.y, factor));
            Tile parent = TILES.get(parentKey);
            if (parent == null) continue;

            int sourceSize = TILE_SIZE / factor;
            int sourceX = Math.floorMod(key.x, factor) * sourceSize;
            int sourceY = Math.floorMod(key.y, factor) * sourceSize;
            parent.lastUsed = now;
            PlatformCompat.drawTextureRegion(context, parent.textureId,
                    drawX, drawY, TILE_SIZE, TILE_SIZE,
                    sourceX, sourceY, sourceSize, sourceSize,
                    TILE_SIZE, TILE_SIZE);
            return true;
        }

        if (key.zoom <= 0) return false;
        int childZoom = key.zoom - 1;
        int childBaseX = key.x * 2;
        int childBaseY = key.y * 2;
        Tile topLeft = TILES.get(new TileKey(key.world, childZoom, childBaseX, childBaseY));
        Tile topRight = TILES.get(new TileKey(key.world, childZoom, childBaseX + 1, childBaseY));
        Tile bottomLeft = TILES.get(new TileKey(key.world, childZoom, childBaseX, childBaseY + 1));
        Tile bottomRight = TILES.get(new TileKey(key.world, childZoom, childBaseX + 1, childBaseY + 1));
        if (topLeft == null || topRight == null || bottomLeft == null || bottomRight == null) {
            return false;
        }

        int half = TILE_SIZE / 2;
        drawFineFallback(context, topLeft, drawX, drawY, half, now);
        drawFineFallback(context, topRight, drawX + half, drawY, half, now);
        drawFineFallback(context, bottomLeft, drawX, drawY + half, half, now);
        drawFineFallback(context, bottomRight, drawX + half, drawY + half, half, now);
        return true;
    }

    private static void drawFineFallback(DrawContext context, Tile tile,
                                         int x, int y, int size, long now) {
        tile.lastUsed = now;
        PlatformCompat.drawTextureRegion(context, tile.textureId,
                x, y, size, size, 0, 0, TILE_SIZE, TILE_SIZE,
                TILE_SIZE, TILE_SIZE);
    }

    private static String activeKey(TileKey key) {
        return key.world + "/" + relativePath(key);
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
        EMPTY_STREAK.remove(key);
        FAILED.remove(key);

        // Dynamic tile paths are deterministic. Registering a refreshed image therefore
        // replaces the texture under the same Identifier. Destroy the old registration
        // first; destroying it after registerDynamicTexture() would delete the freshly
        // uploaded texture and leave the cache pointing at Minecraft's missing texture.
        Tile previous = TILES.remove(key);
        if (previous != null) {
            client.getTextureManager().destroyTexture(previous.textureId);
        }
        Identifier id = PlatformCompat.registerDynamicTexture(client.getTextureManager(),
                "tile/" + key.world + "_" + key.zoom + "_" + key.x + "_" + key.y, image);
        TILES.put(key, new Tile(id, version));
        ACTIVE_BY_PATH.put(activeKey(key), key);
        trimCache(client);
    }

    private static void trimCache(MinecraftClient client) {
        int limit = PlanetEarthMinimapClient.config != null
                && PlanetEarthMinimapClient.config.lowSpecMode
                ? LOW_SPEC_MAX_TILES : NORMAL_MAX_TILES;
        while (TILES.size() > limit) {
            Iterator<Map.Entry<TileKey, Tile>> iterator = TILES.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<TileKey, Tile> oldest = iterator.next();
            for (Map.Entry<TileKey, Tile> entry : TILES.entrySet()) {
                if (entry.getValue().lastUsed < oldest.getValue().lastUsed) oldest = entry;
            }
            if (TILES.remove(oldest.getKey(), oldest.getValue())) {
                client.getTextureManager().destroyTexture(oldest.getValue().textureId);
                if (!PENDING.containsKey(oldest.getKey())) {
                    UPDATE_VERSIONS.remove(oldest.getKey());
                    RETRY_AFTER.remove(oldest.getKey());
                    EMPTY_STREAK.remove(oldest.getKey());
                    ACTIVE_BY_PATH.remove(activeKey(oldest.getKey()), oldest.getKey());
                }
            }
        }
    }

    private static int maxPendingRequests() {
        return PlanetEarthMinimapClient.config != null
                && PlanetEarthMinimapClient.config.lowSpecMode
                ? LOW_SPEC_MAX_PENDING : NORMAL_MAX_PENDING;
    }

    /** Bounds metadata left behind by missing tiles or tiles evicted from the GPU cache. */
    private static void cleanupMetadata(long now) {
        if (now < nextMetadataCleanup) return;
        nextMetadataCleanup = now + Duration.ofSeconds(30).toNanos();
        for (Map.Entry<TileKey, Long> entry : RETRY_AFTER.entrySet()) {
            if (entry.getValue() <= now) RETRY_AFTER.remove(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<TileKey, Long> entry : UPDATE_VERSIONS.entrySet()) {
            TileKey key = entry.getKey();
            if (!TILES.containsKey(key) && !PENDING.containsKey(key)
                    && !READY_UPLOADS.containsKey(key)) {
                UPDATE_VERSIONS.remove(key, entry.getValue());
                ACTIVE_BY_PATH.remove(activeKey(key), key);
            }
        }
    }

    private static int floorDiv(int value, int divisor) {
        return Math.floorDiv(value, divisor);
    }

    private record TileKey(String world, int zoom, int x, int y) {}

    private record PendingUpload(TileKey key, NativeImage image, long version,
                                 CompletableFuture<Void> lifecycle) {}

    private static final class VisibleTileOrder {
        private final String world;
        private final int zoom;
        private final int firstX;
        private final int lastX;
        private final int firstY;
        private final int lastY;
        private final List<TileKey> keys;
        private boolean prefetched;

        private VisibleTileOrder(String world, int zoom, int firstX, int lastX,
                                 int firstY, int lastY, List<TileKey> keys) {
            this.world = world;
            this.zoom = zoom;
            this.firstX = firstX;
            this.lastX = lastX;
            this.firstY = firstY;
            this.lastY = lastY;
            this.keys = keys;
        }

        private boolean matches(String world, int zoom, int firstX, int lastX, int firstY, int lastY) {
            return this.world.equals(world) && this.zoom == zoom && this.firstX == firstX
                    && this.lastX == lastX && this.firstY == firstY && this.lastY == lastY;
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
