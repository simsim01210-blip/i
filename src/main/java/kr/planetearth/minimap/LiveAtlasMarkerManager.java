package kr.planetearth.minimap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** PlanetEarth's public Dynmap marker sets and their original icon textures. */
public final class LiveAtlasMarkerManager {
    public static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("기차역", "기차역");
        CATEGORIES.put("towny.markerset", "Towny");
        CATEGORIES.put("항구", "항구");
        CATEGORIES.put("goods", "특산품");
        CATEGORIES.put("canal", "운하");
        CATEGORIES.put("townykoth.markerset", "TownyKOTH");
    }

    private static final ExecutorService HTTP_EXECUTOR =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "planetearth-marker-http");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(HTTP_EXECUTOR)
            .build();
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final Map<String, Identifier> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> ICON_PENDING = ConcurrentHashMap.newKeySet();
    private static volatile Map<String, MarkerCategory> markerData = Map.of();
    private static volatile long markerPayloadSignature = Long.MIN_VALUE;
    private static volatile String markerEtag;
    private static volatile String markerLastModified;
    // Which Dynmap world markerData was last fetched for — "world" and "worldpvp" are
    // two entirely separate marker feeds (marker_world.json vs marker_worldpvp.json),
    // not just different views of the same data.
    private static volatile String cachedMarkerWorld;
    private static volatile long nextRefresh;
    // Territory fill preparation is expensive on a large full-map viewport. Build an
    // overscanned batch and translate it while panning instead of rebuilding it for
    // every single mouse pixel.
    private static final int MIN_AREA_CACHE_MARGIN = 64;
    private static final int MAX_AREA_CACHE_MARGIN = 192;
    private static boolean[] markerOccupancy = new boolean[0];
    private static AreaRenderCache recentAreaCache;
    private static AreaRenderCache previousAreaCache;
    // Same idea as the area cache, for the minimap/overlay's site markers: recomputing
    // which markers are in view (a category-by-category, bounded scan) every single
    // frame was the direct cause of a real reported slowdown once markers started
    // drawing there too, so it's now only redone when the view pans past the margin.
    private static MarkerRenderCache recentMarkerCache;
    private static MarkerRenderCache previousMarkerCache;
    // Reused every frame for PlatformCompat.fillBatch instead of allocating fresh
    // arrays each time — grown, never shrunk.
    private static int[] batchLeft = new int[256];
    private static int[] batchTop = new int[256];
    private static int[] batchRight = new int[256];
    private static int[] batchBottom = new int[256];
    private static int[] batchColor = new int[256];
    // Reused by the polygon scanline rasterizer. The old List<Double> path boxed every
    // intersection and allocated another double[] for every span on every screen row.
    private static double[] scanlineScratch = new double[64];

    private LiveAtlasMarkerManager() {}

    private static void ensureBatchCapacity(int needed) {
        if (batchLeft.length >= needed) return;
        int newSize = Math.max(needed, batchLeft.length * 2);
        batchLeft = Arrays.copyOf(batchLeft, newSize);
        batchTop = Arrays.copyOf(batchTop, newSize);
        batchRight = Arrays.copyOf(batchRight, newSize);
        batchBottom = Arrays.copyOf(batchBottom, newSize);
        batchColor = Arrays.copyOf(batchColor, newSize);
    }

    public static int count(String category) {
        MarkerCategory data = markerData.get(category);
        return data == null ? 0 : data.markers.size();
    }

    public static List<MarkerEntry> markerEntries(String category) {
        MarkerCategory data = markerData.get(category);
        if (data == null) return List.of();
        boolean inWorldPvp = "worldpvp".equals(
                LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance()));
        List<MarkerEntry> entries = new ArrayList<>(data.markers.size());
        for (MapMarker marker : data.markers) {
            entries.add(new MarkerEntry(displayLabel(marker.label, inWorldPvp), marker.x, marker.z));
        }
        return List.copyOf(entries);
    }

    public static MarkerEntry findMarker(double worldX, double worldZ, double radius,
                                         Set<String> enabledCategories) {
        double bestDistanceSquared = radius * radius;
        MapMarker best = null;
        for (String category : enabledCategories) {
            MarkerCategory data = markerData.get(category);
            if (data == null) continue;
            int start = lowerBoundX(data.markersByX, worldX - radius);
            for (int index = start; index < data.markersByX.size(); index++) {
                MapMarker marker = data.markersByX.get(index);
                if (marker.x > worldX + radius) break;
                double dx = marker.x - worldX;
                double dz = marker.z - worldZ;
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared <= bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    best = marker;
                }
            }
        }
        return best == null ? null : new MarkerEntry(stripHtml(best.label), best.x, best.z);
    }

    public static AreaInfo findArea(double worldX, double worldZ, Set<String> enabledCategories) {
        AreaInfo found = null;
        for (String category : enabledCategories) {
            MarkerCategory data = markerData.get(category);
            if (data == null) continue;
            for (MapArea area : data.areas) {
                if (worldX < area.minX || worldX > area.maxX || worldZ < area.minZ || worldZ > area.maxZ) continue;
                if (insidePolygon(worldX, worldZ, area.x, area.z)) {
                    found = new AreaInfo(CATEGORIES.getOrDefault(category, category),
                            area.country, area.label, area.details);
                }
            }
        }
        return found;
    }

    public static void render(DrawContext context, int mapX, int mapY, int width, int height,
                              double centerWorldX, double centerWorldZ, int zoom,
                              Set<String> enabledCategories, int mouseX, int mouseY) {
        refreshIfNeeded();
        double pixelsPerBlock = 4.0 / (1 << Math.max(0, Math.min(zoom, 7)));
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        MapMarker hovered = null;
        // World PvP's markers are just bare numbers ("4"), which only means something
        // as "4번 포탈" — and its markers are always worth labelling regardless of the
        // general marker-label setting, since there's no other way to tell them apart.
        boolean inWorldPvp = "worldpvp".equals(
                LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance()));

        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            renderAreas(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                    pixelsPerBlock, enabledCategories);
            if (PlanetEarthMinimapClient.config.showAreaLabels) {
                drawAreaLabels(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                        pixelsPerBlock, enabledCategories);
            }
            if (PlanetEarthMinimapClient.config.showSiteMarkers) {
                hovered = drawSiteMarkers(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                        pixelsPerBlock, zoom, enabledCategories, inWorldPvp, mouseX, mouseY);
            }
        } finally {
            context.disableScissor();
        }

        if (hovered != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            String label = displayLabel(hovered.label, inWorldPvp);
            int textWidth = client.textRenderer.getWidth(label);
            int tx = Math.min(mouseX + 10, mapX + width - textWidth - 6);
            int ty = Math.max(mapY + 4, mouseY - 12);
            context.fill(tx - 3, ty - 2, tx + textWidth + 3,
                    ty + client.textRenderer.fontHeight + 2, 0xE0101010);
            context.drawTextWithShadow(client.textRenderer, label, tx, ty, 0xFFFFFFFF);
        }
    }

    /** Draws LiveAtlas area polygons and (unlike the old version) site markers too, for
     *  the compact HUD minimap and the overlay map — those never showed marker icons
     *  or names at all before, only territory colour. */
    public static void renderAreaOverlay(DrawContext context, int mapX, int mapY,
                                         int width, int height, double centerWorldX,
                                         double centerWorldZ, int zoom) {
        refreshIfNeeded();
        double pixelsPerBlock = 4.0 / (1 << Math.max(0, Math.min(zoom, 7)));
        boolean inWorldPvp = "worldpvp".equals(
                LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance()));
        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            renderAreas(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                    pixelsPerBlock, CATEGORIES.keySet());
            if (PlanetEarthMinimapClient.config.showAreaLabels) {
                drawAreaLabels(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                        pixelsPerBlock, CATEGORIES.keySet());
            }
            if (PlanetEarthMinimapClient.config.showSiteMarkers) {
                drawSiteMarkersCached(context, mapX, mapY, width, height, centerWorldX, centerWorldZ,
                        pixelsPerBlock, zoom, CATEGORIES.keySet(), inWorldPvp);
            }
        } finally {
            context.disableScissor();
        }
    }

    /** Draws every visible site marker's icon (and, if enabled, its persistent label).
     *  Shared by the full map screen (which also hit-tests the cursor for a hover
     *  tooltip — pass real mouseX/mouseY) and the minimap/overlay (which have no
     *  cursor to hover with — pass (-1, -1) to skip that check entirely). Returns
     *  whichever marker the cursor is over, or null. */
    private static MapMarker drawSiteMarkers(DrawContext context, int mapX, int mapY, int width, int height,
                                              double centerWorldX, double centerWorldZ, double pixelsPerBlock,
                                              int zoom, Set<String> enabledCategories, boolean inWorldPvp,
                                              int mouseX, int mouseY) {
        MapMarker hovered = null;
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        int markerSize = displayMarkerSize(zoom);
        int hitRadius = Math.max(4, markerSize / 2);
        // At world-scale zoom hundreds of icons can land on the same few pixels.
        // Drawing all of those hidden layers only adds GPU work, so collapse
        // overlapping screen buckets at the two farthest levels.
        int markerBucketSize = Math.max(6, markerSize * 3 / 4);
        int bucketZoom = PlanetEarthMinimapClient.config.lowSpecMode ? 4 : 5;
        int markerBucketColumns = zoom >= bucketZoom
                ? Math.floorDiv(width + markerBucketSize * 2 - 1, markerBucketSize) + 1 : 0;
        int markerBucketRows = zoom >= bucketZoom
                ? Math.floorDiv(height + markerBucketSize * 2 - 1, markerBucketSize) + 1 : 0;
        int markerBucketCount = markerBucketColumns * markerBucketRows;
        if (markerBucketCount > 0) {
            if (markerOccupancy.length < markerBucketCount) {
                markerOccupancy = new boolean[markerBucketCount];
            } else {
                java.util.Arrays.fill(markerOccupancy, 0, markerBucketCount, false);
            }
        }
        for (String category : enabledCategories) {
            MarkerCategory data = markerData.get(category);
            if (data == null) continue;
            double minMarkerWorldX = centerWorldX - (width / 2.0 + markerSize) / pixelsPerBlock;
            double maxMarkerWorldX = centerWorldX + (width / 2.0 + markerSize) / pixelsPerBlock;
            int firstMarker = lowerBoundX(data.markersByX, minMarkerWorldX);
            for (int markerIndex = firstMarker; markerIndex < data.markersByX.size(); markerIndex++) {
                MapMarker marker = data.markersByX.get(markerIndex);
                if (marker.x > maxMarkerWorldX) break;
                int x = centerX + (int) Math.round((marker.x - centerWorldX) * pixelsPerBlock);
                int y = centerY + (int) Math.round((marker.z - centerWorldZ) * pixelsPerBlock);
                if (x < mapX - markerSize || x > mapX + width + markerSize
                        || y < mapY - markerSize || y > mapY + height + markerSize) continue;
                if (markerBucketCount > 0) {
                    int bucketX = Math.floorDiv(x - mapX + markerBucketSize, markerBucketSize);
                    int bucketY = Math.floorDiv(y - mapY + markerBucketSize, markerBucketSize);
                    int bucket = bucketY * markerBucketColumns + bucketX;
                    if (bucket < 0 || bucket >= markerBucketCount || markerOccupancy[bucket]) continue;
                    markerOccupancy[bucket] = true;
                }
                drawIcon(context, marker.icon, x, y, markerSize);
                if (PlanetEarthMinimapClient.config.showMarkerLabels || inWorldPvp) {
                    drawMarkerLabel(context, displayLabel(marker.label, inWorldPvp), x, y, markerSize);
                }
                if (mouseX >= 0 && Math.abs(mouseX - x) <= hitRadius && Math.abs(mouseY - y) <= hitRadius) {
                    hovered = marker;
                }
            }
        }
        return hovered;
    }

    /** Same visual result as {@link #drawSiteMarkers} (icons + optional labels, no
     *  hover — the minimap/overlay have no cursor), but for the minimap/overlay
     *  specifically: which markers are in view is cached with an overscanned margin
     *  and only rebuilt once the view pans past it, the same way {@link #renderAreas}
     *  already caches territory geometry. Scanning every category from scratch every
     *  single frame was a real, reported slowdown once markers started drawing on the
     *  always-on minimap and the (much bigger) overlay map, not just the full map
     *  screen the underlying scan was originally written for. */
    private static void drawSiteMarkersCached(DrawContext context, int mapX, int mapY, int width, int height,
                                              double centerWorldX, double centerWorldZ, double pixelsPerBlock,
                                              int zoom, Set<String> enabledCategories, boolean inWorldPvp) {
        int markerSize = displayMarkerSize(zoom);
        int cacheMargin = areaCacheMargin(width, height);
        Map<String, MarkerCategory> snapshot = markerData;
        MarkerRenderCache batch = findMarkerCache(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, pixelsPerBlock);
        if (batch == null) {
            List<MarkerDrawEntry> entries = new ArrayList<>();
            double halfWorldWidth = (width / 2.0 + cacheMargin) / pixelsPerBlock;
            double halfWorldHeight = (height / 2.0 + cacheMargin) / pixelsPerBlock;
            double minWorldX = centerWorldX - halfWorldWidth;
            double maxWorldX = centerWorldX + halfWorldWidth;
            double minWorldZ = centerWorldZ - halfWorldHeight;
            double maxWorldZ = centerWorldZ + halfWorldHeight;
            for (String category : enabledCategories) {
                MarkerCategory data = snapshot.get(category);
                if (data == null) continue;
                int firstMarker = lowerBoundX(data.markersByX, minWorldX);
                for (int i = firstMarker; i < data.markersByX.size(); i++) {
                    MapMarker marker = data.markersByX.get(i);
                    if (marker.x > maxWorldX) break;
                    if (marker.z < minWorldZ || marker.z > maxWorldZ) continue;
                    entries.add(new MarkerDrawEntry(marker.x, marker.z, marker.icon, marker.label));
                    // Pre-warm icons for the whole overscanned margin, not just the
                    // exact viewport, so panning to the edge doesn't pop in blank spots.
                    requestIcon(marker.icon);
                }
            }
            batch = new MarkerRenderCache(snapshot, Set.copyOf(enabledCategories), mapX, mapY,
                    width, height, centerWorldX, centerWorldZ, pixelsPerBlock, cacheMargin,
                    List.copyOf(entries));
            cacheMarkerBatch(batch);
        }

        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        for (MarkerDrawEntry entry : batch.entries) {
            int x = centerX + (int) Math.round((entry.worldX - centerWorldX) * pixelsPerBlock);
            int y = centerY + (int) Math.round((entry.worldZ - centerWorldZ) * pixelsPerBlock);
            if (x < mapX - markerSize || x > mapX + width + markerSize
                    || y < mapY - markerSize || y > mapY + height + markerSize) continue;
            drawIcon(context, entry.icon, x, y, markerSize);
            if (PlanetEarthMinimapClient.config.showMarkerLabels || inWorldPvp) {
                drawMarkerLabel(context, displayLabel(entry.label, inWorldPvp), x, y, markerSize);
            }
        }
    }

    private static MarkerRenderCache findMarkerCache(
            Map<String, MarkerCategory> snapshot, Set<String> enabledCategories,
            int mapX, int mapY, int width, int height,
            double centerWorldX, double centerWorldZ, double scale) {
        if (recentMarkerCache != null && recentMarkerCache.matches(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, scale)) {
            return recentMarkerCache;
        }
        if (previousMarkerCache != null && previousMarkerCache.matches(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, scale)) {
            MarkerRenderCache found = previousMarkerCache;
            previousMarkerCache = recentMarkerCache;
            recentMarkerCache = found;
            return found;
        }
        return null;
    }

    private static void cacheMarkerBatch(MarkerRenderCache batch) {
        previousMarkerCache = recentMarkerCache;
        recentMarkerCache = batch;
    }

    private record MarkerDrawEntry(double worldX, double worldZ, String icon, String label) {}

    private static final class MarkerRenderCache {
        private final Map<String, MarkerCategory> snapshot;
        private final Set<String> categories;
        private final int mapX;
        private final int mapY;
        private final int width;
        private final int height;
        private final double centerWorldX;
        private final double centerWorldZ;
        private final long scaleBits;
        private final int margin;
        private final List<MarkerDrawEntry> entries;

        private MarkerRenderCache(Map<String, MarkerCategory> snapshot, Set<String> categories,
                                  int mapX, int mapY, int width, int height,
                                  double centerWorldX, double centerWorldZ, double scale,
                                  int margin, List<MarkerDrawEntry> entries) {
            this.snapshot = snapshot;
            this.categories = categories;
            this.mapX = mapX;
            this.mapY = mapY;
            this.width = width;
            this.height = height;
            this.centerWorldX = centerWorldX;
            this.centerWorldZ = centerWorldZ;
            this.scaleBits = Double.doubleToLongBits(scale);
            this.margin = margin;
            this.entries = entries;
        }

        private boolean matches(Map<String, MarkerCategory> snapshot,
                                Set<String> enabledCategories,
                                int mapX, int mapY, int width, int height,
                                double centerWorldX, double centerWorldZ, double scale) {
            return this.snapshot == snapshot
                    && this.mapX == mapX && this.mapY == mapY
                    && this.width == width && this.height == height
                    && scaleBits == Double.doubleToLongBits(scale)
                    && Math.abs((this.centerWorldX - centerWorldX) * scale) <= margin - 2
                    && Math.abs((this.centerWorldZ - centerWorldZ) * scale) <= margin - 2
                    && categories.equals(enabledCategories);
        }
    }

    private static void renderAreas(DrawContext context, int mapX, int mapY, int width, int height,
                                    double centerWorldX, double centerWorldZ, double scale,
                                    Set<String> enabledCategories) {
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        int cacheMargin = areaCacheMargin(width, height);
        Map<String, MarkerCategory> snapshot = markerData;
        AreaRenderCache batch = findAreaCache(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, scale);
        if (batch == null) {
            List<MapArea> visible = new ArrayList<>();
            double halfWorldWidth = (width / 2.0 + cacheMargin) / scale;
            double halfWorldHeight = (height / 2.0 + cacheMargin) / scale;
            double minVisibleX = centerWorldX - halfWorldWidth;
            double maxVisibleX = centerWorldX + halfWorldWidth;
            double minVisibleZ = centerWorldZ - halfWorldHeight;
            double maxVisibleZ = centerWorldZ + halfWorldHeight;
            for (String category : enabledCategories) {
                MarkerCategory data = snapshot.get(category);
                if (data == null) continue;
                for (MapArea area : data.areas) {
                    if (area.maxX < minVisibleX || area.minX > maxVisibleX
                            || area.maxZ < minVisibleZ || area.minZ > maxVisibleZ) continue;
                    visible.add(area);
                }
            }

            Map<Integer, Map<Integer, List<int[]>>> spansByColor = new LinkedHashMap<>();
            int clipLeft = mapX - cacheMargin;
            int clipTop = mapY - cacheMargin;
            int clipRight = mapX + width + cacheMargin;
            int clipBottom = mapY + height + cacheMargin;
            for (MapArea area : visible) {
                // Preserve both the RGB and fill opacity supplied by LiveAtlas.
                int r = (area.fillColor >> 16) & 0xFF;
                int g = (area.fillColor >> 8) & 0xFF;
                int b = area.fillColor & 0xFF;
                int a = MathHelper.clamp(Math.round(area.fillOpacity * 255), 0, 255);
                if (a == 0) continue;
                int color = a << 24 | r << 16 | g << 8 | b;
                Map<Integer, List<int[]>> rows = spansByColor.computeIfAbsent(color,
                        ignored -> new HashMap<>());

                // Sample the polygon once per *screen* row (at that row's own world-Z
                // centre) instead of once per world chunk row and letting whichever
                // rows that band happens to span inherit its width. At low zoom several
                // differently-shaped chunk rows (e.g. a stair-step notch versus the
                // claim's main body) can land on the very same screen row; unioning
                // their widths there used to fatten that row out past the claim's own
                // outline. Sampling at render resolution keeps the fill matched to
                // whatever the outline actually draws, at any zoom level.
                int areaTop = Math.max(clipTop,
                        centerY + (int) Math.floor((area.minZ - centerWorldZ) * scale));
                int areaBottom = Math.min(clipBottom,
                        centerY + (int) Math.ceil((area.maxZ - centerWorldZ) * scale));
                for (int row = areaTop; row < areaBottom; row++) {
                    double sampleWorldZ = centerWorldZ + (row + 0.5 - centerY) / scale;
                    int intersections = scanlineIntersections(area.x, area.z, sampleWorldZ);
                    for (int interval = 0; interval + 1 < intersections; interval += 2) {
                        double intervalLeft = scanlineScratch[interval];
                        double intervalRight = scanlineScratch[interval + 1];
                        if (intervalRight <= intervalLeft) continue;
                        int left = centerX + (int) Math.floor((intervalLeft - centerWorldX) * scale);
                        int right = centerX + (int) Math.ceil((intervalRight - centerWorldX) * scale);
                        if (right <= clipLeft || left >= clipRight) continue;
                        left = Math.max(clipLeft, left);
                        right = Math.min(clipRight, Math.max(left + 1, right));
                        rows.computeIfAbsent(row, ignored -> new ArrayList<>())
                                .add(new int[]{left, right});
                    }
                }
            }

            List<ColoredRectangle> rectangles = new ArrayList<>();
            for (Map.Entry<Integer, Map<Integer, List<int[]>>> colorEntry : spansByColor.entrySet()) {
                collectMergedSpans(rectangles, colorEntry.getValue(), colorEntry.getKey());
            }
            Set<ColoredRectangle> uniqueOutlines = new LinkedHashSet<>();
            List<ColoredLine> diagonalOutlines = new ArrayList<>();
            for (MapArea area : visible) {
                int r = (area.lineColor >> 16) & 0xFF;
                int g = (area.lineColor >> 8) & 0xFF;
                int b = area.lineColor & 0xFF;
                int a = MathHelper.clamp((int) (area.lineOpacity * 255), 0, 255);
                int color = a << 24 | r << 16 | g << 8 | b;
                for (int i = 0; i < area.x.length; i++) {
                    int next = (i + 1) % area.x.length;
                    int x1 = centerX + (int) Math.round((area.x[i] - centerWorldX) * scale);
                    int y1 = centerY + (int) Math.round((area.z[i] - centerWorldZ) * scale);
                    int x2 = centerX + (int) Math.round((area.x[next] - centerWorldX) * scale);
                    int y2 = centerY + (int) Math.round((area.z[next] - centerWorldZ) * scale);
                    cacheOutline(uniqueOutlines, diagonalOutlines, x1, y1, x2, y2,
                            clipLeft, clipTop, clipRight - 1, clipBottom - 1, color);
                }
            }
            batch = new AreaRenderCache(snapshot, Set.copyOf(enabledCategories), mapX, mapY,
                    width, height, centerWorldX, centerWorldZ, scale,
                    cacheMargin,
                    List.copyOf(rectangles), mergeOutlineSegments(uniqueOutlines),
                    List.copyOf(diagonalOutlines));
            cacheAreaBatch(batch);
        }

        int fillShiftX = (int) Math.round((batch.centerWorldX - centerWorldX) * scale);
        int fillShiftY = (int) Math.round((batch.centerWorldZ - centerWorldZ) * scale);

        // Every rectangle used to be its own context.fill() call — its own GPU draw
        // call. A dense Towny city could mean well over a hundred of those every
        // single frame even though nothing changed since the last one (the cache only
        // avoids recomputing the geometry, not resubmitting it). Collecting them into
        // plain int arrays and submitting the whole batch as one draw call via
        // PlatformCompat.fillBatch cuts that down to two draw calls total (one for
        // fills, one for outlines) regardless of how many rectangles there are.
        ensureBatchCapacity(Math.max(batch.rectangles.size(), batch.outlines.size()));
        int fillCount = 0;
        for (ColoredRectangle rectangle : batch.rectangles) {
            int left = rectangle.left + fillShiftX;
            int top = rectangle.top + fillShiftY;
            int right = rectangle.right + fillShiftX;
            int bottom = rectangle.bottom + fillShiftY;
            if (right <= mapX || left >= mapX + width || bottom <= mapY || top >= mapY + height) continue;
            batchLeft[fillCount] = left;
            batchTop[fillCount] = top;
            batchRight[fillCount] = right;
            batchBottom[fillCount] = bottom;
            batchColor[fillCount] = rectangle.color;
            fillCount++;
        }
        PlatformCompat.fillBatch(context, batchLeft, batchTop, batchRight, batchBottom, batchColor, fillCount);

        int outlineCount = 0;
        for (ColoredRectangle outline : batch.outlines) {
            int left = outline.left + fillShiftX;
            int top = outline.top + fillShiftY;
            int right = outline.right + fillShiftX;
            int bottom = outline.bottom + fillShiftY;
            if (right <= mapX || left >= mapX + width || bottom <= mapY || top >= mapY + height) continue;
            batchLeft[outlineCount] = left;
            batchTop[outlineCount] = top;
            batchRight[outlineCount] = right;
            batchBottom[outlineCount] = bottom;
            batchColor[outlineCount] = outline.color;
            outlineCount++;
        }
        PlatformCompat.fillBatch(context, batchLeft, batchTop, batchRight, batchBottom, batchColor, outlineCount);

        for (ColoredLine line : batch.diagonalOutlines) {
            drawClippedLine(context, line.x1 + fillShiftX, line.y1 + fillShiftY,
                    line.x2 + fillShiftX, line.y2 + fillShiftY,
                    mapX, mapY, mapX + width - 1, mapY + height - 1, line.color);
        }
    }

    private static void cacheOutline(Set<ColoredRectangle> rectangles,
                                     List<ColoredLine> diagonalLines,
                                     int x1, int y1, int x2, int y2,
                                     int minX, int minY, int maxX, int maxY, int color) {
        if (y1 == y2) {
            if (y1 < minY || y1 > maxY) return;
            int left = Math.max(minX, Math.min(x1, x2));
            int right = Math.min(maxX, Math.max(x1, x2));
            if (left <= right) rectangles.add(
                    new ColoredRectangle(left, y1, right + 1, y1 + 1, color));
            return;
        }
        if (x1 == x2) {
            if (x1 < minX || x1 > maxX) return;
            int top = Math.max(minY, Math.min(y1, y2));
            int bottom = Math.min(maxY, Math.max(y1, y2));
            if (top <= bottom) rectangles.add(
                    new ColoredRectangle(x1, top, x1 + 1, bottom + 1, color));
            return;
        }
        if (Math.max(x1, x2) < minX || Math.min(x1, x2) > maxX
                || Math.max(y1, y2) < minY || Math.min(y1, y2) > maxY) return;
        diagonalLines.add(new ColoredLine(x1, y1, x2, y2, color));
    }

    /** Border outlines start as one 1px rectangle per chunk edge (16 blocks), so a long
     *  straight town border spanning many chunks used to become one draw call per
     *  chunk. Merging same-row/same-column, same-colour, touching segments into fewer,
     *  wider rectangles collapses that back down to one draw call per straight run —
     *  a real GPU draw-call cost on weaker hardware, same idea as the fill merging above. */
    private static List<ColoredRectangle> mergeOutlineSegments(Collection<ColoredRectangle> segments) {
        Map<Long, List<ColoredRectangle>> horizontalRows = new HashMap<>();
        Map<Long, List<ColoredRectangle>> verticalColumns = new HashMap<>();
        List<ColoredRectangle> merged = new ArrayList<>();
        for (ColoredRectangle rect : segments) {
            if (rect.bottom - rect.top == 1) {
                long key = ((long) rect.top << 32) ^ (rect.color & 0xFFFFFFFFL);
                horizontalRows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rect);
            } else {
                long key = ((long) rect.left << 32) ^ (rect.color & 0xFFFFFFFFL);
                verticalColumns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rect);
            }
        }
        for (List<ColoredRectangle> row : horizontalRows.values()) {
            row.sort(Comparator.comparingInt(r -> r.left));
            int start = row.get(0).left;
            int end = row.get(0).right;
            for (int i = 1; i < row.size(); i++) {
                ColoredRectangle r = row.get(i);
                if (r.left <= end) {
                    end = Math.max(end, r.right);
                } else {
                    merged.add(new ColoredRectangle(start, row.get(0).top, end, row.get(0).bottom, row.get(0).color));
                    start = r.left;
                    end = r.right;
                }
            }
            merged.add(new ColoredRectangle(start, row.get(0).top, end, row.get(0).bottom, row.get(0).color));
        }
        for (List<ColoredRectangle> column : verticalColumns.values()) {
            column.sort(Comparator.comparingInt(r -> r.top));
            int start = column.get(0).top;
            int end = column.get(0).bottom;
            for (int i = 1; i < column.size(); i++) {
                ColoredRectangle r = column.get(i);
                if (r.top <= end) {
                    end = Math.max(end, r.bottom);
                } else {
                    merged.add(new ColoredRectangle(column.get(0).left, start, column.get(0).right, end, column.get(0).color));
                    start = r.top;
                    end = r.bottom;
                }
            }
            merged.add(new ColoredRectangle(column.get(0).left, start, column.get(0).right, end, column.get(0).color));
        }
        return merged;
    }

    private static AreaRenderCache findAreaCache(
            Map<String, MarkerCategory> snapshot, Set<String> enabledCategories,
            int mapX, int mapY, int width, int height,
            double centerWorldX, double centerWorldZ, double scale) {
        if (recentAreaCache != null && recentAreaCache.matches(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, scale)) {
            return recentAreaCache;
        }
        if (previousAreaCache != null && previousAreaCache.matches(snapshot, enabledCategories,
                mapX, mapY, width, height, centerWorldX, centerWorldZ, scale)) {
            AreaRenderCache found = previousAreaCache;
            previousAreaCache = recentAreaCache;
            recentAreaCache = found;
            return found;
        }
        return null;
    }

    private static void cacheAreaBatch(AreaRenderCache batch) {
        previousAreaCache = recentAreaCache;
        recentAreaCache = batch;
    }

    private static int areaCacheMargin(int width, int height) {
        return MathHelper.clamp(Math.max(width, height) / 3,
                MIN_AREA_CACHE_MARGIN, MAX_AREA_CACHE_MARGIN);
    }

    /** Merges equal spans on adjacent rows into cached rectangles. */
    private static void collectMergedSpans(List<ColoredRectangle> output,
                                           Map<Integer, List<int[]>> rows, int color) {
        List<Integer> orderedRows = new ArrayList<>(rows.keySet());
        orderedRows.sort(Integer::compareTo);
        Map<Long, int[]> active = new HashMap<>();
        int previousRow = Integer.MIN_VALUE;
        for (int row : orderedRows) {
            if (previousRow != Integer.MIN_VALUE && row != previousRow + 1) {
                for (int[] rectangle : active.values()) addRectangle(output, rectangle, color);
                active.clear();
            }

            List<int[]> spans = rows.get(row);
            spans.sort(Comparator.comparingInt(span -> span[0]));
            Map<Long, int[]> next = new HashMap<>();
            int start = spans.get(0)[0];
            int end = spans.get(0)[1];
            for (int index = 1; index <= spans.size(); index++) {
                int[] span = index < spans.size() ? spans.get(index) : null;
                if (span != null && span[0] <= end) {
                    end = Math.max(end, span[1]);
                    continue;
                }

                long key = ((long) start << 32) ^ (end & 0xFFFFFFFFL);
                int[] rectangle = active.remove(key);
                if (rectangle != null && rectangle[3] == row) {
                    rectangle[3] = row + 1;
                } else {
                    if (rectangle != null) addRectangle(output, rectangle, color);
                    rectangle = new int[]{start, row, end, row + 1};
                }
                next.put(key, rectangle);
                if (span != null) {
                    start = span[0];
                    end = span[1];
                }
            }
            for (int[] rectangle : active.values()) addRectangle(output, rectangle, color);
            active = next;
            previousRow = row;
        }
        for (int[] rectangle : active.values()) addRectangle(output, rectangle, color);
    }

    private static void addRectangle(List<ColoredRectangle> output, int[] rectangle, int color) {
        output.add(new ColoredRectangle(
                rectangle[0], rectangle[1], rectangle[2], rectangle[3], color));
    }

    private static void drawClippedLine(DrawContext context, int x1, int y1, int x2, int y2,
                                        int minX, int minY, int maxX, int maxY, int color) {
        double ax = x1;
        double ay = y1;
        double bx = x2;
        double by = y2;
        while (true) {
            int first = outCode(ax, ay, minX, minY, maxX, maxY);
            int second = outCode(bx, by, minX, minY, maxX, maxY);
            if ((first | second) == 0) break;
            if ((first & second) != 0) return;
            int outside = first != 0 ? first : second;
            double clippedX;
            double clippedY;
            if ((outside & 8) != 0) {
                clippedX = ax + (bx - ax) * (maxY - ay) / (by - ay);
                clippedY = maxY;
            } else if ((outside & 4) != 0) {
                clippedX = ax + (bx - ax) * (minY - ay) / (by - ay);
                clippedY = minY;
            } else if ((outside & 2) != 0) {
                clippedY = ay + (by - ay) * (maxX - ax) / (bx - ax);
                clippedX = maxX;
            } else {
                clippedY = ay + (by - ay) * (minX - ax) / (bx - ax);
                clippedX = minX;
            }
            if (outside == first) {
                ax = clippedX;
                ay = clippedY;
            } else {
                bx = clippedX;
                by = clippedY;
            }
        }
        drawLine(context, (int) Math.round(ax), (int) Math.round(ay),
                (int) Math.round(bx), (int) Math.round(by), color);
    }

    private static int outCode(double x, double y,
                               int minX, int minY, int maxX, int maxY) {
        int code = 0;
        if (x < minX) code |= 1;
        else if (x > maxX) code |= 2;
        if (y < minY) code |= 4;
        else if (y > maxY) code |= 8;
        return code;
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        // Towny boundaries are almost entirely axis-aligned. Submitting one rectangle
        // instead of one GUI fill per pixel removes thousands of draw calls on the
        // full-world screen while preserving the exact one-pixel outline.
        if (y1 == y2) {
            context.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, color);
            return;
        }
        if (x1 == x2) {
            context.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, color);
            return;
        }
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int stepX = x1 < x2 ? 1 : -1;
        int stepY = y1 < y2 ? 1 : -1;
        int error = dx - dy;
        while (true) {
            context.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) return;
            int doubled = error * 2;
            if (doubled > -dy) {
                error -= dy;
                x1 += stepX;
            }
            if (doubled < dx) {
                error += dx;
                y1 += stepY;
            }
        }
    }

    private static float labelScale() {
        return MathHelper.clamp(PlanetEarthMinimapClient.config.showMapLabelScalePercent, 25, 200) / 100.0f;
    }

    /** One persistent name label, horizontally centred on centerX with its top edge at
     *  topY (both already in real, unscaled GUI pixels) — shared by marker and area
     *  labels so the size slider affects both the same way. */
    private static void drawScaledLabel(DrawContext context, String text, int centerX, int topY, float scale) {
        MinecraftClient client = MinecraftClient.getInstance();
        int rawWidth = client.textRenderer.getWidth(text);
        int scaledWidth = Math.round(rawWidth * scale);
        int scaledHeight = Math.round(client.textRenderer.fontHeight * scale);
        int x = centerX - scaledWidth / 2;
        context.fill(x - 2, topY - 1, x + scaledWidth + 2, topY + scaledHeight + 1, 0xA0101010);
        if (Math.abs(scale - 1.0f) < 0.001f) {
            context.drawTextWithShadow(client.textRenderer, text, x, topY, 0xFFFFFFFF);
            return;
        }
        PlatformCompat.push(context);
        PlatformCompat.translate(context, x, topY);
        PlatformCompat.scale(context, scale, scale);
        context.drawTextWithShadow(client.textRenderer, text, 0, 0, 0xFFFFFFFF);
        PlatformCompat.pop(context);
    }

    /** World PvP's own markers are labelled with a bare number ("4"), which only reads
     *  as "4번 포탈" — everywhere else a marker's label is shown as-is. */
    private static String displayLabel(String rawLabel, boolean inWorldPvp) {
        if (inWorldPvp && rawLabel != null && !rawLabel.isEmpty()
                && rawLabel.chars().allMatch(Character::isDigit)) {
            return rawLabel + "번 포탈";
        }
        return rawLabel;
    }

    /** One persistent name label per marker instead of only on hover — placed just
     *  below its icon, with a small backdrop so it stays readable over any tile. */
    private static void drawMarkerLabel(DrawContext context, String label, int x, int y, int size) {
        if (label == null || label.isBlank()) return;
        drawScaledLabel(context, label, x, y + size / 2 + 2, labelScale());
    }

    /** One persistent name label per visible territory, centred on its bounding box —
     *  the country name (if LiveAtlas provided one) or the area's own label otherwise. */
    private static void drawAreaLabels(DrawContext context, int mapX, int mapY, int width, int height,
                                       double centerWorldX, double centerWorldZ, double scale,
                                       Set<String> enabledCategories) {
        MinecraftClient client = MinecraftClient.getInstance();
        float textScale = labelScale();
        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        double halfWorldWidth = width / 2.0 / scale;
        double halfWorldHeight = height / 2.0 / scale;
        double minVisibleX = centerWorldX - halfWorldWidth;
        double maxVisibleX = centerWorldX + halfWorldWidth;
        double minVisibleZ = centerWorldZ - halfWorldHeight;
        double maxVisibleZ = centerWorldZ + halfWorldHeight;
        Map<String, MarkerCategory> snapshot = markerData;
        for (String category : enabledCategories) {
            MarkerCategory data = snapshot.get(category);
            if (data == null) continue;
            for (MapArea area : data.areas) {
                if (area.maxX < minVisibleX || area.minX > maxVisibleX
                        || area.maxZ < minVisibleZ || area.minZ > maxVisibleZ) continue;
                String text = "정보 없음".equals(area.country) ? area.label : area.country;
                if (text == null || text.isBlank()) continue;
                double worldCenterX = (area.minX + area.maxX) / 2.0;
                double worldCenterZ = (area.minZ + area.maxZ) / 2.0;
                int x = centerX + (int) Math.round((worldCenterX - centerWorldX) * scale);
                int y = centerY + (int) Math.round((worldCenterZ - centerWorldZ) * scale);
                int topY = y - Math.round(client.textRenderer.fontHeight * textScale / 2f);
                drawScaledLabel(context, text, x, topY, textScale);
            }
        }
    }

    private static int displayMarkerSize(int zoom) {
        int base = PlanetEarthMinimapClient.config.siteMarkerSize;
        double zoomFactor = Math.pow(1.14, 3 - MathHelper.clamp(zoom, 0, 7));
        return MathHelper.clamp((int) Math.round(base * zoomFactor), 5, 30);
    }

    private static void drawIcon(DrawContext context, String iconName, int x, int y, int size) {
        Identifier icon = ICONS.get(iconName);
        if (icon == null) {
            requestIcon(iconName);
            int radius = Math.max(2, size / 4);
            context.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, 0xFFFFD84D);
            return;
        }
        PlatformCompat.push(context);
        PlatformCompat.translate(context, x - size / 2.0f, y - size / 2.0f);
        float scale = size / 16.0f;
        PlatformCompat.scale(context, scale, scale);
        PlatformCompat.drawTexture(context, icon, 0, 0, 0, 0,
                16, 16, 16, 16);
        PlatformCompat.pop(context);
    }

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh || !PENDING.compareAndSet(false, true)) return;
        nextRefresh = now + 2_000;
        // World PvP has its own separate marker feed (marker_worldpvp.json) — without
        // this, its markers were the overworld's Towny towns/goods/ports (Pretoria,
        // 카카오, ...) drawn at whatever screen position their overworld X/Z happened to
        // land on in the World PvP view, on top of World PvP's own correctly-fetched
        // map tiles. Same class of bug as the tile/player fixes, just for this feed too.
        String world = LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance());
        if (world == null) {
            if (!markerData.isEmpty()) markerData = Map.of();
            markerPayloadSignature = Long.MIN_VALUE;
            cachedMarkerWorld = null;
            PENDING.set(false);
            return;
        }
        if (!world.equals(cachedMarkerWorld)) {
            // A different world means a different URL entirely — the previous
            // ETag/Last-Modified belong to that other feed and would be meaningless
            // (or simply wrong) sent against this one.
            markerEtag = null;
            markerLastModified = null;
            markerPayloadSignature = Long.MIN_VALUE;
        }
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        HttpRequest.Builder builder = requestBuilder(base + "/tiles/_markers_/marker_" + world + ".json");
        String etag = markerEtag;
        String lastModified = markerLastModified;
        if (etag != null && !etag.isBlank()) builder.header("If-None-Match", etag);
        if (lastModified != null && !lastModified.isBlank()) {
            builder.header("If-Modified-Since", lastModified);
        }
        HTTP.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 304) return;
                    if (response.statusCode() != 200) return;
                    response.headers().firstValue("ETag").ifPresent(value -> markerEtag = value);
                    response.headers().firstValue("Last-Modified")
                            .ifPresent(value -> markerLastModified = value);
                    String body = response.body();
                    long signature = ((long) body.length() << 32)
                            ^ (body.hashCode() & 0xFFFFFFFFL);
                    if (signature == markerPayloadSignature && !markerData.isEmpty()) return;
                    JsonObject sets = JsonParser.parseString(body).getAsJsonObject()
                            .getAsJsonObject("sets");
                    Map<String, MarkerCategory> updated = new LinkedHashMap<>();
                    for (String category : CATEGORIES.keySet()) {
                        List<MapMarker> categoryMarkers = new ArrayList<>();
                        List<MapArea> categoryAreas = new ArrayList<>();
                        JsonObject set = sets.getAsJsonObject(category);
                        if (set != null && set.has("markers")) {
                            for (Map.Entry<String, JsonElement> entry : set.getAsJsonObject("markers").entrySet()) {
                                JsonObject value = entry.getValue().getAsJsonObject();
                                categoryMarkers.add(new MapMarker(
                                        value.has("label") ? value.get("label").getAsString() : entry.getKey(),
                                        value.has("icon") ? value.get("icon").getAsString() : "default",
                                        value.get("x").getAsDouble(), value.get("z").getAsDouble()
                                ));
                            }
                        }
                        if (set != null && set.has("areas")) {
                            for (Map.Entry<String, JsonElement> entry : set.getAsJsonObject("areas").entrySet()) {
                                JsonObject value = entry.getValue().getAsJsonObject();
                                double[] xs = toDoubleArray(value.getAsJsonArray("x"));
                                double[] zs = toDoubleArray(value.getAsJsonArray("z"));
                                if (xs.length < 3 || xs.length != zs.length) continue;
                                if (xs.length > 3 && xs[0] == xs[xs.length - 1] && zs[0] == zs[zs.length - 1]) {
                                    xs = java.util.Arrays.copyOf(xs, xs.length - 1);
                                    zs = java.util.Arrays.copyOf(zs, zs.length - 1);
                                }
                                categoryAreas.add(createArea(value, xs, zs));
                            }
                        }
                        List<MapMarker> markersByX = new ArrayList<>(categoryMarkers);
                        markersByX.sort(Comparator.comparingDouble(marker -> marker.x));
                        updated.put(category, new MarkerCategory(
                                List.copyOf(categoryMarkers), List.copyOf(markersByX),
                                List.copyOf(categoryAreas)));
                    }
                    markerData = Map.copyOf(updated);
                    markerPayloadSignature = signature;
                    cachedMarkerWorld = world;
                    // Download every icon used by the completed snapshot up front. Rendering then only
                    // swaps atomic snapshots instead of filling markers gradually as the map moves.
                    for (MarkerCategory data : updated.values()) {
                        for (MapMarker marker : data.markers) requestIcon(marker.icon);
                    }
                })
                .exceptionally(error -> {
                    PlanetEarthMinimapClient.LOGGER.warn("Could not update LiveAtlas markers", error);
                    return null;
                })
                .whenComplete((unused, error) -> PENDING.set(false));
    }

    private static void requestIcon(String iconName) {
        if (iconName == null || iconName.isBlank() || ICONS.containsKey(iconName)
                || !ICON_PENDING.add(iconName)) return;
        if (ICONS.containsKey(iconName)) {
            ICON_PENDING.remove(iconName);
            return;
        }
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        HTTP.sendAsync(request(base + "/tiles/_markers_/" + iconName + ".png"),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        ICON_PENDING.remove(iconName);
                        return;
                    }
                    try {
                        NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()));
                        try {
                            MinecraftClient.getInstance().execute(() -> uploadIcon(iconName, image));
                        } catch (RuntimeException exception) {
                            image.close();
                            ICON_PENDING.remove(iconName);
                            throw exception;
                        }
                    } catch (Exception exception) {
                        ICON_PENDING.remove(iconName);
                        PlanetEarthMinimapClient.LOGGER.debug("Could not decode marker icon {}", iconName, exception);
                    }
                })
                .exceptionally(error -> {
                    ICON_PENDING.remove(iconName);
                    return null;
                });
    }

    private static void uploadIcon(String iconName, NativeImage image) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            String safe = iconName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            Identifier previous = ICONS.remove(iconName);
            if (previous != null) client.getTextureManager().destroyTexture(previous);
            Identifier id = PlatformCompat.registerDynamicTexture(
                    client.getTextureManager(), "marker/" + safe, image);
            ICONS.put(iconName, id);
        } finally {
            ICON_PENDING.remove(iconName);
        }
    }

    private static HttpRequest request(String url) {
        return requestBuilder(url).GET().build();
    }

    private static HttpRequest.Builder requestBuilder(String url) {
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "PlanetEarthMinimap/0.8")
                .header("Referer", base + "/");
    }

    private static double[] toDoubleArray(com.google.gson.JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
        return values;
    }

    private static MapArea createArea(JsonObject value, double[] x, double[] z) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < x.length; i++) {
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]);
            maxZ = Math.max(maxZ, z[i]);
        }
        int fillColor = parseColor(value, "fillcolor", 0xFFFFFF);
        int lineColor = parseColor(value, "color", fillColor);
        float fillOpacity = value.has("fillopacity") ? value.get("fillopacity").getAsFloat() : 0.25f;
        float lineOpacity = value.has("opacity") ? value.get("opacity").getAsFloat() : 0.8f;
        String label = value.has("label") ? value.get("label").getAsString() : "영역";
        String details = value.has("desc") ? stripHtml(value.get("desc").getAsString()) : label;
        String country = extractCountry(details, label);
        return new MapArea(x, z, label, country, details,
                fillColor, lineColor,
                fillOpacity, lineOpacity, minX, maxX, minZ, maxZ);
    }

    private static int parseColor(JsonObject value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        String text = value.get(key).getAsString().replace("#", "");
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * X-intervals of the polygon at the given world Z, via the standard even-odd
     * scanline rule. Used to fill one screen row at a time, sampled at that row's own
     * world-Z centre — see the call site in {@link #renderAreas} for why.
     */
    private static int scanlineIntersections(double[] x, double[] z, double sampleZ) {
        if (scanlineScratch.length < x.length) {
            int newSize = scanlineScratch.length;
            while (newSize < x.length) newSize *= 2;
            scanlineScratch = Arrays.copyOf(scanlineScratch, newSize);
        }
        int count = 0;
        for (int i = 0; i < x.length; i++) {
            int next = (i + 1) % x.length;
            double z1 = z[i];
            double z2 = z[next];
            if ((z1 <= sampleZ && z2 > sampleZ) || (z2 <= sampleZ && z1 > sampleZ)) {
                double ratio = (sampleZ - z1) / (z2 - z1);
                scanlineScratch[count++] = x[i] + (x[next] - x[i]) * ratio;
            }
        }
        Arrays.sort(scanlineScratch, 0, count);
        return count;
    }

    private static boolean insidePolygon(double px, double pz, double[] x, double[] z) {
        boolean inside = false;
        for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
            if (((z[i] > pz) != (z[j] > pz))
                    && px < (x[j] - x[i]) * (pz - z[i]) / (z[j] - z[i]) + x[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static int lowerBoundX(List<MapMarker> markers, double x) {
        int low = 0;
        int high = markers.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (markers.get(middle).x < x) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static String stripHtml(String html) {
        return html.replace("<br />", " ").replace("<br/>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    private static String extractCountry(String details, String label) {
        int labelEnd = details.indexOf(label) + label.length();
        if (labelEnd < label.length()) return "정보 없음";
        int open = details.indexOf('(', labelEnd);
        int close = open < 0 ? -1 : details.indexOf(')', open + 1);
        if (open < 0 || close < 0) return "정보 없음";
        String country = details.substring(open + 1, close).trim();
        return country.isEmpty() ? "정보 없음" : country;
    }

    private record MapMarker(String label, String icon, double x, double z) {}

    private record MarkerCategory(List<MapMarker> markers, List<MapMarker> markersByX,
                                  List<MapArea> areas) {}

    public record AreaInfo(String category, String country, String town, String details) {}

    public record MarkerEntry(String name, double x, double z) {}

    private record ColoredRectangle(int left, int top, int right, int bottom, int color) {}

    private record ColoredLine(int x1, int y1, int x2, int y2, int color) {}

    private static final class AreaRenderCache {
        private final Map<String, MarkerCategory> snapshot;
        private final Set<String> categories;
        private final int mapX;
        private final int mapY;
        private final int width;
        private final int height;
        private final double centerWorldX;
        private final double centerWorldZ;
        private final long scaleBits;
        private final int margin;
        private final List<ColoredRectangle> rectangles;
        private final List<ColoredRectangle> outlines;
        private final List<ColoredLine> diagonalOutlines;

        private AreaRenderCache(Map<String, MarkerCategory> snapshot, Set<String> categories,
                                int mapX, int mapY, int width, int height,
                                double centerWorldX, double centerWorldZ, double scale,
                                int margin,
                                List<ColoredRectangle> rectangles,
                                List<ColoredRectangle> outlines,
                                List<ColoredLine> diagonalOutlines) {
            this.snapshot = snapshot;
            this.categories = categories;
            this.mapX = mapX;
            this.mapY = mapY;
            this.width = width;
            this.height = height;
            this.centerWorldX = centerWorldX;
            this.centerWorldZ = centerWorldZ;
            this.scaleBits = Double.doubleToLongBits(scale);
            this.margin = margin;
            this.rectangles = rectangles;
            this.outlines = outlines;
            this.diagonalOutlines = diagonalOutlines;
        }

        private boolean matches(Map<String, MarkerCategory> snapshot,
                                Set<String> enabledCategories,
                                int mapX, int mapY, int width, int height,
                                double centerWorldX, double centerWorldZ, double scale) {
            return this.snapshot == snapshot
                    && this.mapX == mapX && this.mapY == mapY
                    && this.width == width && this.height == height
                    && scaleBits == Double.doubleToLongBits(scale)
                    && Math.abs((this.centerWorldX - centerWorldX) * scale)
                            <= margin - 2
                    && Math.abs((this.centerWorldZ - centerWorldZ) * scale)
                            <= margin - 2
                    && categories.equals(enabledCategories);
        }
    }

    private record MapArea(double[] x, double[] z,
                           String label, String country, String details,
                           int fillColor, int lineColor, float fillOpacity, float lineOpacity,
                           double minX, double maxX, double minZ, double maxZ) {}
}
