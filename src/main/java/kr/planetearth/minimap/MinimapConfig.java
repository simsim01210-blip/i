package kr.planetearth.minimap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class MinimapConfig {
    /** Finite JSON-safe sentinel used only for legacy waypoints that have no saved Y. */
    public static final double UNKNOWN_WAYPOINT_Y = -1_000_000.0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("planetearth-minimap.json");

    public int x = 12;
    public int y = 12;
    public int width = 150;
    public int height = 150;
    public boolean enabled = true;
    public boolean showGrid = true;
    public boolean showAreaOverlay = true;
    public boolean showPlayers = true;
    public boolean showPlayerFaces = true;
    public boolean showPlayerNames = true;
    public int playerFaceSize = 8;
    public int playerNameScalePercent = 75;
    public int selfMarkerSize = 9;
    public boolean uiSoundsEnabled = true;
    public int uiSoundVolumePercent = 35;
    public int zoom = 2;
    public String mapUrl = "https://map.planetearth.kr/";
    private transient volatile String cachedMapUrlSource;
    private transient volatile String cachedMapBaseUrl;
    public List<Waypoint> waypoints = new ArrayList<>();
    public boolean showSiteMarkers = true;
    public int siteMarkerSize = 12;
    public boolean showWaypoints = true;
    public int waypointSize = 9;
    public String waypointShape = WaypointPalette.SHAPE_PIN;
    /** One of "both", "name", "distance", "none" — cycled by a single button rather
     *  than several separate toggles. */
    public String waypointLabelMode = "both";
    public int waypointLabelScalePercent = 100;
    public int overlayMapWidth = 500;
    public int overlayMapHeight = 500;
    public int overlayMapOffsetX = 0;
    public int overlayMapOffsetY = 0;
    public int overlayZoom = 3;
    public boolean showStatusBar = true;
    public int statusBarX = 12;
    public int statusBarY = 170;
    public int statusBarScalePercent = 100;
    /** Bundles several individually-heavier settings into one switch for weaker PCs:
     *  forces the territory colour overlay off, skips prefetching tiles just outside
     *  the viewport, and shrinks the tile download/decode thread pool. See
     *  {@link PlanetEarthMinimapClient#applyLowSpecMode} for where it's applied. */
    public boolean lowSpecMode = false;
    /** Persistent site marker name labels (기차역/특산품/항구/... and, on the full map
     *  screen only, town flags) next to each icon — instead of only showing on hover.
     *  Shown on the corner minimap and the overlay map too, not just the full map screen. */
    public boolean showMarkerLabels = true;
    /** Persistent country/town name labels centred in each coloured territory —
     *  independent of showMarkerLabels, since one clutters the whole map with big
     *  land-area names and the other just labels individual points. */
    public boolean showAreaLabels = false;
    public int showMapLabelScalePercent = 100;

    public static MinimapConfig load() {
        if (!Files.exists(PATH)) {
            return new MinimapConfig();
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            MinimapConfig config = GSON.fromJson(reader, MinimapConfig.class);
            if (config == null) return new MinimapConfig();
            config.sanitize();
            return config;
        } catch (Exception exception) {
            PlanetEarthMinimapClient.LOGGER.warn("Could not load minimap config", exception);
            return new MinimapConfig();
        }
    }

    public synchronized void save() {
        sanitize();
        Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
        try {
            // Serialize completely before opening either file. A malformed value can no
            // longer truncate the player's last valid configuration halfway through a save.
            String json = GSON.toJson(this);
            Files.createDirectories(PATH.getParent());
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            PlanetEarthMinimapClient.LOGGER.warn("Could not save minimap config", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                PlanetEarthMinimapClient.LOGGER.debug(
                        "Could not remove temporary minimap config", cleanupException);
            }
        }
    }

    /**
     * Returns the configured map URL without trailing slashes. Tile, marker and
     * player refreshes call this very frequently, so avoid running a regular
     * expression for every request. The source comparison still makes edits to
     * {@link #mapUrl} take effect immediately.
     */
    public String mapBaseUrl() {
        String source = mapUrl;
        if (source == null || source.isBlank()) source = "https://map.planetearth.kr/";
        String cachedSource = cachedMapUrlSource;
        String cachedBase = cachedMapBaseUrl;
        if (source.equals(cachedSource) && cachedBase != null) return cachedBase;

        int minimumEnd = Math.max(0, source.indexOf("://") + 3);
        int end = source.length();
        while (end > minimumEnd && source.charAt(end - 1) == '/') end--;
        String normalized = source.substring(0, end);
        cachedMapUrlSource = source;
        cachedMapBaseUrl = normalized;
        return normalized;
    }

    public void sanitize() {
        // Minimum kept low on purpose: on a 4K display with a high GUI scale, even this
        // scaled-pixel minimum can render as a large box in real screen pixels, so
        // players need the option to shrink it much further than a 1080p-tuned floor.
        width = Math.max(24, Math.min(width, 480));
        height = Math.max(24, Math.min(height, 480));
        zoom = Math.max(0, Math.min(zoom, 6));
        playerFaceSize = Math.max(2, Math.min(playerFaceSize, 16));
        playerNameScalePercent = Math.max(25, Math.min(playerNameScalePercent, 150));
        selfMarkerSize = Math.max(7, Math.min(selfMarkerSize, 17));
        uiSoundVolumePercent = Math.max(0, Math.min(uiSoundVolumePercent, 100));
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (mapUrl == null || mapUrl.isBlank()) mapUrl = "https://map.planetearth.kr/";
        if (waypoints == null) waypoints = new ArrayList<>();
        // Old releases used Double.NaN as the missing-height marker. Gson rejects NaN
        // while saving, which crashed the client from the keyPressed/close handler. Drop
        // irrecoverably invalid coordinates and convert only the optional height to a
        // finite sentinel that the HUD anchors to the player's current level later.
        for (int index = waypoints.size() - 1; index >= 0; index--) {
            Waypoint waypoint = waypoints.get(index);
            if (waypoint == null || !Double.isFinite(waypoint.x)
                    || !Double.isFinite(waypoint.z)) {
                waypoints.remove(index);
            }
        }
        for (int index = 0; index < waypoints.size(); index++) {
            Waypoint waypoint = waypoints.get(index);
            if (!Double.isFinite(waypoint.y)) waypoint.y = UNKNOWN_WAYPOINT_Y;
            if (waypoint.name == null || waypoint.name.isBlank()) {
                waypoint.name = "웨이포인트 " + (index + 1);
            }
            waypoint.color = WaypointPalette.normalize(waypoint.color, index);
            waypoint.shape = WaypointPalette.normalizeShape(waypoint.shape);
        }
        siteMarkerSize = Math.max(6, Math.min(siteMarkerSize, 24));
        waypointSize = Math.max(1, Math.min(waypointSize, 24));
        waypointShape = WaypointPalette.normalizeShape(waypointShape);
        if (!"name".equals(waypointLabelMode) && !"distance".equals(waypointLabelMode)
                && !"none".equals(waypointLabelMode)) {
            waypointLabelMode = "both";
        }
        waypointLabelScalePercent = Math.max(25, Math.min(waypointLabelScalePercent, 150));
        overlayMapWidth = Math.max(24, Math.min(overlayMapWidth, 900));
        overlayMapHeight = Math.max(24, Math.min(overlayMapHeight, 900));
        overlayMapOffsetX = Math.max(-800, Math.min(overlayMapOffsetX, 800));
        overlayMapOffsetY = Math.max(-800, Math.min(overlayMapOffsetY, 800));
        overlayZoom = Math.max(0, Math.min(overlayZoom, 7));
        statusBarX = Math.max(0, statusBarX);
        statusBarY = Math.max(0, statusBarY);
        statusBarScalePercent = Math.max(25, Math.min(statusBarScalePercent, 200));
        showMapLabelScalePercent = Math.max(25, Math.min(showMapLabelScalePercent, 200));
    }

    public static final class Waypoint {
        public String name;
        public double x;
        public double y = UNKNOWN_WAYPOINT_Y;
        public double z;
        public int color;
        public String shape = WaypointPalette.SHAPE_PIN;

        public Waypoint() {
        }

        public Waypoint(String name, double x, double z) {
            this(name, x, UNKNOWN_WAYPOINT_Y, z, WaypointPalette.COLORS[0]);
        }

        public Waypoint(String name, double x, double y, double z) {
            this(name, x, y, z, WaypointPalette.COLORS[0]);
        }

        public Waypoint(String name, double x, double y, double z, int color) {
            this(name, x, y, z, color, WaypointPalette.SHAPE_PIN);
        }

        public Waypoint(String name, double x, double y, double z,
                        int color, String shape) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = WaypointPalette.normalize(color, 0);
            this.shape = WaypointPalette.normalizeShape(shape);
        }
    }
}
