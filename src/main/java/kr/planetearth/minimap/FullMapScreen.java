package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

abstract class FullMapScreenBase extends Screen {
    private static final int SIDEBAR_WIDTH = 200;
    private static final int CONTEXT_WIDTH = 142;
    private static final int CONTEXT_HEIGHT = 22;
    private static final int WAYPOINT_PANEL_WIDTH = 190;
    private static final int WAYPOINT_PANEL_HEIGHT = 105;
    private static final int COLOR_SWATCH_SIZE = 12;
    private static final int COLOR_SWATCH_STEP = 14;
    private static final int NAVIGATION_PANEL_WIDTH = 190;
    private static final int NAVIGATION_PANEL_HEIGHT = 58;

    private final Set<String> enabledCategories =
            new LinkedHashSet<>(LiveAtlasMarkerManager.CATEGORIES.keySet());
    private final Map<String, ButtonWidget> categoryButtons = new LinkedHashMap<>();
    private double centerWorldX;
    private double centerWorldZ;
    private int zoom = 4;
    private boolean initializedCenter;
    private boolean panning;
    private ButtonWidget siteMarkerButton;
    private ButtonWidget markerLabelButton;
    private ButtonWidget areaLabelButton;
    private ButtonWidget waypointButton;
    // Captured live from rebuildSidebar() instead of hand-counted from row heights —
    // a hardcoded formula here silently drifted out of sync (and started overlapping
    // the button below it) the last two times a row was added above this section.
    private int waypointHeadingY;
    private ButtonWidget playerButton;
    private ButtonWidget playerSearchButton;
    private String openedCategory;
    private boolean playerBrowserOpen;
    private int markerScroll;
    private int sidebarScroll;
    private int sidebarMaxScroll;
    private int openedMarkerCount = -1;
    private long openedPlayerRosterRevision = -1;
    private String markerSearch = "";
    private TextFieldWidget markerSearchField;
    private boolean focusMarkerSearch;
    private ButtonWidget markerBackButton;
    private final List<ButtonWidget> markerResultButtons = new ArrayList<>();
    private int lastSidebarStateHash = Integer.MIN_VALUE;

    private boolean waypointMenuOpen;
    private int waypointMenuX;
    private int waypointMenuY;
    private double pendingWaypointX;
    private double pendingWaypointZ;
    private boolean waypointNameOpen;
    private int waypointNamePanelX;
    private int waypointNamePanelY;
    private TextFieldWidget waypointNameField;
    private int pendingWaypointColor = WaypointPalette.COLORS[0];
    private String pendingWaypointShape = WaypointPalette.SHAPE_PIN;
    private boolean navigationPanelOpen;
    private int navigationPanelX;
    private int navigationPanelY;
    private String navigationTargetName;
    private double navigationTargetX;
    private double navigationTargetZ;
    private boolean mapClickCandidate;
    private boolean mapDragged;
    private double mapPressX;
    private double mapPressY;

    private LiveAtlasMarkerManager.AreaInfo selectedArea;
    private int areaPopupX;
    private int areaPopupY;

    protected FullMapScreenBase() {
        super(Text.literal("PlanetEarth 전체 지도"));
    }

    @Override
    protected void init() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!initializedCenter && client.player != null) {
            centerWorldX = client.player.getX();
            centerWorldZ = client.player.getZ();
            initializedCenter = true;
        }
        rebuildSidebar();
    }

    private void rebuildSidebar() {
        clearChildren();
        lastSidebarStateHash = Integer.MIN_VALUE;
        categoryButtons.clear();
        markerResultButtons.clear();
        markerBackButton = null;
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int panelX = width - SIDEBAR_WIDTH + 8;
        int controlWidth = SIDEBAR_WIDTH - 16;
        if (playerBrowserOpen) {
            rebuildPlayerBrowser(panelX, controlWidth);
            return;
        }
        if (openedCategory != null) {
            rebuildMarkerBrowser(panelX, controlWidth);
            return;
        }
        // The whole panel — categories, settings and the waypoint list — scrolls as one
        // unit so anything below the fold (in particular a waypoint's "삭제" button once
        // there are enough waypoints or the window is short) is still reachable, rather
        // than the old behaviour of silently stopping and never creating those widgets.
        int y = 30 - sidebarScroll;

        for (Map.Entry<String, String> category : LiveAtlasMarkerManager.CATEGORIES.entrySet()) {
            String id = category.getKey();
            ButtonWidget button = ButtonWidget.builder(categoryText(id, category.getValue()), pressed -> {
                playerBrowserOpen = false;
                openedCategory = id;
                markerScroll = 0;
                markerSearch = "";
                focusMarkerSearch = true;
                rebuildSidebar();
            }).dimensions(panelX, y, controlWidth, 20).build();
            categoryButtons.put(id, button);
            addDrawableChild(button);
            y += 22;
        }

        y += 14;
        playerSearchButton = addDrawableChild(ButtonWidget.builder(playerSearchText(), pressed -> {
            openedCategory = null;
            playerBrowserOpen = true;
            markerScroll = 0;
            markerSearch = "";
            focusMarkerSearch = true;
            rebuildSidebar();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;
        playerButton = addDrawableChild(ButtonWidget.builder(playerText(), pressed -> {
            config.showPlayers = !config.showPlayers;
            pressed.setMessage(playerText());
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;

        siteMarkerButton = addDrawableChild(ButtonWidget.builder(siteMarkerText(), pressed -> {
            config.showSiteMarkers = !config.showSiteMarkers;
            pressed.setMessage(siteMarkerText());
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;
        addDrawableChild(new IntSlider(panelX, y, controlWidth,
                "사이트 마커 크기", 6, 24, config.siteMarkerSize, value -> {
            config.siteMarkerSize = value;
        }));
        y += 30;

        // Persistent labels instead of only-on-hover, split into two independent
        // switches: each site marker's own name next to its icon (also shown on the
        // corner minimap and the overlay map), and separately the country/town name
        // centred in each coloured territory — one clutters the map with big land-area
        // names, the other just labels individual points, so they don't have to be
        // both on or both off together.
        markerLabelButton = addDrawableChild(ButtonWidget.builder(markerLabelText(), pressed -> {
            config.showMarkerLabels = !config.showMarkerLabels;
            pressed.setMessage(markerLabelText());
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;
        areaLabelButton = addDrawableChild(ButtonWidget.builder(areaLabelText(), pressed -> {
            config.showAreaLabels = !config.showAreaLabels;
            pressed.setMessage(areaLabelText());
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;
        addDrawableChild(new IntSlider(panelX, y, controlWidth,
                "이름 표시 크기", 25, 200, config.showMapLabelScalePercent, value -> {
            config.showMapLabelScalePercent = value;
        }));
        y += 22;

        // A blank gap first (matches the categories → player-search spacing above) so
        // the heading below actually has room to sit in, instead of overlapping
        // whatever row came right before it — captured live rather than hand-counted
        // so it always tracks wherever this row actually lands, scroll included.
        y += 14;
        waypointHeadingY = y - 12;
        waypointButton = addDrawableChild(ButtonWidget.builder(waypointText(), pressed -> {
            config.showWaypoints = !config.showWaypoints;
            pressed.setMessage(waypointText());
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 22;
        addDrawableChild(new IntSlider(panelX, y, controlWidth,
                "웨이포인트 크기", 1, 24, config.waypointSize, value -> {
            config.waypointSize = value;
        }));
        y += 22;
        addDrawableChild(new IntSlider(panelX, y, controlWidth,
                "웨이포인트 글씨 크기", 25, 150, config.waypointLabelScalePercent, value -> {
            config.waypointLabelScalePercent = value;
        }));
        y += 22;
        addDrawableChild(ButtonWidget.builder(waypointLabelModeText(config), pressed -> {
            config.waypointLabelMode = nextWaypointLabelMode(config.waypointLabelMode);
            pressed.setMessage(waypointLabelModeText(config));
            config.save();
        }).dimensions(panelX, y, controlWidth, 20).build());
        y += 28;

        int index = 0;
        for (MinimapConfig.Waypoint waypoint : config.waypoints) {
            int number = ++index;
            // Every waypoint gets its row's widgets regardless of whether it's currently
            // scrolled into view — skipping off-screen ones used to mean a waypoint far
            // down the list could never get a "삭제" button made for it at all.
            if (y + 20 >= 0 && y <= height) {
                addDrawableChild(ButtonWidget.builder(Text.literal(number + ". " + waypoint.name), pressed -> {
                    centerWorldX = waypoint.x;
                    centerWorldZ = waypoint.z;
                }).dimensions(panelX, y, controlWidth - 62, 20).build());
                addDrawableChild(ButtonWidget.builder(
                        Text.literal("■").styled(style -> style.withColor(waypoint.color)), pressed -> {
                            waypoint.color = WaypointPalette.next(waypoint.color);
                            config.save();
                            rebuildSidebar();
                        }).dimensions(panelX + controlWidth - 62, y, 20, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("삭제"), pressed -> {
                    config.waypoints.remove(waypoint);
                    config.save();
                    rebuildSidebar();
                }).dimensions(panelX + controlWidth - 40, y, 40, 20).build());
            }
            y += 22;
        }

        // y (still shifted by -sidebarScroll) plus the scroll amount gives back the true,
        // unshifted content height, which is what the scroll range is clamped against.
        sidebarMaxScroll = Math.max(0, y + sidebarScroll - height + 8);
        if (sidebarScroll > sidebarMaxScroll) {
            sidebarScroll = sidebarMaxScroll;
        }
    }

    private void rebuildMarkerBrowser(int panelX, int controlWidth) {
        String category = openedCategory;
        String label = LiveAtlasMarkerManager.CATEGORIES.getOrDefault(category, category);
        markerBackButton = addDrawableChild(ButtonWidget.builder(
                Text.literal("← " + label), pressed -> {
                    openedCategory = null;
                    markerScroll = 0;
                    markerSearch = "";
                    markerSearchField = null;
                    rebuildSidebar();
                }).dimensions(panelX, 30, controlWidth, 20).build());

        markerSearchField = new TextFieldWidget(textRenderer, panelX, 55,
                controlWidth, 20, Text.literal("마커 검색"));
        markerSearchField.setMaxLength(50);
        markerSearchField.setPlaceholder(Text.literal("마커 검색..."));
        markerSearchField.setText(markerSearch);
        markerSearchField.setChangedListener(value -> {
            if (value.equals(markerSearch)) return;
            markerSearch = value;
            markerScroll = 0;
            // Preserve this exact TextFieldWidget while Korean IME composition is in
            // progress. Recreating it on every syllable used to drop or skip Hangul.
            rebuildMarkerResults(panelX, controlWidth);
        });
        addDrawableChild(markerSearchField);
        if (focusMarkerSearch) {
            setFocused(markerSearchField);
            markerSearchField.setFocused(true);
            focusMarkerSearch = false;
        }

        rebuildMarkerResults(panelX, controlWidth);
    }

    private void rebuildPlayerBrowser(int panelX, int controlWidth) {
        markerBackButton = addDrawableChild(ButtonWidget.builder(
                Text.literal("← 온라인 플레이어"), pressed -> {
                    playerBrowserOpen = false;
                    markerScroll = 0;
                    markerSearch = "";
                    markerSearchField = null;
                    rebuildSidebar();
                }).dimensions(panelX, 30, controlWidth, 20).build());

        markerSearchField = new TextFieldWidget(textRenderer, panelX, 55,
                controlWidth, 20, Text.literal("플레이어 검색"));
        markerSearchField.setMaxLength(50);
        markerSearchField.setPlaceholder(Text.literal("닉네임 검색..."));
        markerSearchField.setText(markerSearch);
        markerSearchField.setChangedListener(value -> {
            if (value.equals(markerSearch)) return;
            markerSearch = value;
            markerScroll = 0;
            rebuildPlayerResults(panelX, controlWidth);
        });
        addDrawableChild(markerSearchField);
        if (focusMarkerSearch) {
            setFocused(markerSearchField);
            markerSearchField.setFocused(true);
            focusMarkerSearch = false;
        }

        rebuildPlayerResults(panelX, controlWidth);
    }

    private void rebuildPlayerResults(int panelX, int controlWidth) {
        for (ButtonWidget button : markerResultButtons) remove(button);
        markerResultButtons.clear();
        if (!playerBrowserOpen) return;

        List<LiveAtlasPlayerManager.PlayerEntry> allPlayers =
                LiveAtlasPlayerManager.playerEntries();
        List<LiveAtlasPlayerManager.PlayerEntry> players = filteredPlayerEntries(allPlayers);
        openedPlayerRosterRevision = LiveAtlasPlayerManager.rosterRevision();
        String countText = markerSearch.isBlank()
                ? Integer.toString(allPlayers.size())
                : players.size() + "/" + allPlayers.size();
        if (markerBackButton != null) {
            markerBackButton.setMessage(Text.literal("← 온라인 플레이어 (" + countText + ")"));
        }

        int listY = 80;
        int visibleRows = Math.max(1, (height - listY - 10) / 22);
        int maxScroll = Math.max(0, players.size() - visibleRows);
        markerScroll = MathHelper.clamp(markerScroll, 0, maxScroll);
        int end = Math.min(players.size(), markerScroll + visibleRows);
        for (int i = markerScroll; i < end; i++) {
            LiveAtlasPlayerManager.PlayerEntry player = players.get(i);
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(
                    Text.literal(player.name()), pressed -> {
                LiveAtlasPlayerManager.PlayerEntry latest =
                        LiveAtlasPlayerManager.findPlayer(player.account());
                if (latest == null) latest = player;
                centerWorldX = latest.x();
                centerWorldZ = latest.z();
                selectedArea = null;
                waypointMenuOpen = false;
                navigationPanelOpen = false;
            }).dimensions(panelX, listY + (i - markerScroll) * 22, controlWidth, 20).build());
            markerResultButtons.add(button);
        }
    }

    private void rebuildMarkerResults(int panelX, int controlWidth) {
        for (ButtonWidget button : markerResultButtons) remove(button);
        markerResultButtons.clear();
        if (openedCategory == null) return;

        String label = LiveAtlasMarkerManager.CATEGORIES.getOrDefault(openedCategory, openedCategory);
        List<LiveAtlasMarkerManager.MarkerEntry> allMarkers =
                LiveAtlasMarkerManager.markerEntries(openedCategory);
        List<LiveAtlasMarkerManager.MarkerEntry> markers = filteredMarkerEntries(allMarkers);
        openedMarkerCount = allMarkers.size();
        String countText = markerSearch.isBlank()
                ? Integer.toString(allMarkers.size())
                : markers.size() + "/" + allMarkers.size();
        if (markerBackButton != null) {
            markerBackButton.setMessage(Text.literal("← " + label + " (" + countText + ")"));
        }

        int listY = 80;
        int visibleRows = Math.max(1, (height - listY - 10) / 22);
        int maxScroll = Math.max(0, markers.size() - visibleRows);
        markerScroll = MathHelper.clamp(markerScroll, 0, maxScroll);
        int end = Math.min(markers.size(), markerScroll + visibleRows);
        for (int i = markerScroll; i < end; i++) {
            LiveAtlasMarkerManager.MarkerEntry marker = markers.get(i);
            String position = String.format(" [%.0f, %.0f]", marker.x(), marker.z());
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(
                    Text.literal(marker.name() + position), pressed -> {
                centerWorldX = marker.x();
                centerWorldZ = marker.z();
                selectedArea = null;
                waypointMenuOpen = false;
            }).dimensions(panelX, listY + (i - markerScroll) * 22, controlWidth, 20).build());
            markerResultButtons.add(button);
        }
    }

    private List<LiveAtlasMarkerManager.MarkerEntry> filteredMarkerEntries(
            List<LiveAtlasMarkerManager.MarkerEntry> markers) {
        if (markerSearch == null || markerSearch.isBlank()) return markers;
        String needle = Normalizer.normalize(markerSearch.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        return markers.stream()
                .filter(marker -> Normalizer.normalize(marker.name(), Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private List<LiveAtlasPlayerManager.PlayerEntry> filteredPlayerEntries(
            List<LiveAtlasPlayerManager.PlayerEntry> players) {
        if (markerSearch == null || markerSearch.isBlank()) return players;
        String needle = Normalizer.normalize(markerSearch.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        return players.stream()
                .filter(player -> Normalizer.normalize(player.name(), Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private Text categoryText(String id, String label) {
        return Text.literal("▶ " + label + " (" + LiveAtlasMarkerManager.count(id) + ")");
    }

    private Text siteMarkerText() {
        return Text.literal("사이트 마커: " +
                (PlanetEarthMinimapClient.config.showSiteMarkers ? "켜짐" : "꺼짐"));
    }

    private Text playerText() {
        return Text.literal("다른 플레이어: " +
                (PlanetEarthMinimapClient.config.showPlayers ? "켜짐" : "꺼짐"));
    }

    private Text playerSearchText() {
        return Text.literal("온라인 플레이어 검색 (" +
                LiveAtlasPlayerManager.playerCount() + ")");
    }

    private Text waypointText() {
        return Text.literal("내 웨이포인트: " +
                (PlanetEarthMinimapClient.config.showWaypoints ? "켜짐" : "꺼짐"));
    }

    private Text markerLabelText() {
        return Text.literal("마커 이름 표시: " +
                (PlanetEarthMinimapClient.config.showMarkerLabels ? "켜짐" : "꺼짐"));
    }

    private Text areaLabelText() {
        return Text.literal("영역 이름 표시: " +
                (PlanetEarthMinimapClient.config.showAreaLabels ? "켜짐" : "꺼짐"));
    }

    /** One button cycling through every waypoint label mode instead of several separate
     *  toggles: 전체 켜기 → 거리만 보기 → 이름만 보기 → 전체 끄기 → (다시 전체 켜기). */
    private static String nextWaypointLabelMode(String current) {
        return switch (current) {
            case "distance" -> "name";
            case "name" -> "none";
            case "none" -> "both";
            default -> "distance";
        };
    }

    private static Text waypointLabelModeText(MinimapConfig config) {
        String suffix = switch (config.waypointLabelMode) {
            case "distance" -> "거리만";
            case "name" -> "이름만";
            case "none" -> "끄기";
            default -> "전체";
        };
        return Text.literal("웨이포인트 표시: " + suffix);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (playerBrowserOpen
                && openedPlayerRosterRevision != LiveAtlasPlayerManager.rosterRevision()) {
            rebuildPlayerResults(width - SIDEBAR_WIDTH + 8, SIDEBAR_WIDTH - 16);
        }
        if (openedCategory != null
                && openedMarkerCount != LiveAtlasMarkerManager.count(openedCategory)) {
            rebuildMarkerResults(width - SIDEBAR_WIDTH + 8, SIDEBAR_WIDTH - 16);
        }
        int mapWidth = mapWidth();
        context.fill(0, 0, width, height, 0xFF101010);
        context.fill(0, 0, mapWidth, height, 0xFF173426);
        LiveAtlasTileManager.render(context, 0, 0, mapWidth, height,
                centerWorldX, centerWorldZ, zoom);
        LiveAtlasMarkerManager.render(context, 0, 0, mapWidth, height,
                centerWorldX, centerWorldZ, zoom, enabledCategories, mouseX, mouseY);
        if (PlanetEarthMinimapClient.config.showPlayers) {
            LiveAtlasPlayerManager.render(context, 0, 0, mapWidth, height,
                    centerWorldX, centerWorldZ, zoom);
        }
        drawWaypoints(context, mapWidth);
        NavigationManager.renderOnFullMap(context, 0, 0, mapWidth, height,
                centerWorldX, centerWorldZ, pixelsPerBlock());
        drawLocalPlayer(context, mapWidth);
        drawPendingWaypoint(context, mapWidth);

        context.fill(mapWidth, 0, width, height, 0xF0181818);
        context.fill(mapWidth, 0, mapWidth + 2, height, 0xFF8B8B8B);
        context.drawCenteredTextWithShadow(textRenderer, title,
                mapWidth + SIDEBAR_WIDTH / 2, 7, 0xFFFFFFFF);
        if (openedCategory == null && !playerBrowserOpen) {
            context.drawTextWithShadow(textRenderer, Text.literal("사이트 영역 / 마커"),
                    mapWidth + 10, 19, 0xFFBFBFBF);
            context.drawTextWithShadow(textRenderer, Text.literal("내 웨이포인트"),
                    mapWidth + 10, waypointHeadingY, 0xFFFFD95A);
        } else if (playerBrowserOpen) {
            context.drawTextWithShadow(textRenderer, Text.literal("플레이어 목록 · 휠로 스크롤"),
                    mapWidth + 10, 19, 0xFFBFBFBF);
        } else {
            context.drawTextWithShadow(textRenderer, Text.literal("마커 목록 · 휠로 스크롤"),
                    mapWidth + 10, 19, 0xFFBFBFBF);
        }
        context.drawTextWithShadow(textRenderer,
                Text.literal(String.format("중심 %.0f, %.0f  |  배율 %d", centerWorldX, centerWorldZ, zoom)),
                8, 8, 0xFFFFFFFF);
        drawNavigationStatus(context, mapWidth);
        context.drawTextWithShadow(textRenderer,
                Text.literal("좌클릭: 영역 정보 / 드래그 이동  |  휠: 확대·축소  |  우클릭: 지정 메뉴"),
                8, height - 14, 0xFFFFFFFF);

        refreshSidebarLabels();
        drawWaypointNamePanel(context);
        super.render(context, mouseX, mouseY, delta);

        drawAreaPopup(context, mapWidth);
        drawWaypointMenu(context, mapWidth);
        drawNavigationPanel(context, mapWidth);
    }

    private void refreshSidebarLabels() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int state = config.showSiteMarkers ? 1 : 0;
        state = state * 31 + (config.showWaypoints ? 1 : 0);
        state = state * 31 + (config.showPlayers ? 1 : 0);
        state = state * 31 + LiveAtlasPlayerManager.playerCount();
        for (String category : LiveAtlasMarkerManager.CATEGORIES.keySet()) {
            state = state * 31 + LiveAtlasMarkerManager.count(category);
        }
        if (state == lastSidebarStateHash) return;
        lastSidebarStateHash = state;
        for (Map.Entry<String, ButtonWidget> entry : categoryButtons.entrySet()) {
            entry.getValue().setMessage(categoryText(entry.getKey(),
                    LiveAtlasMarkerManager.CATEGORIES.get(entry.getKey())));
        }
        if (siteMarkerButton != null) siteMarkerButton.setMessage(siteMarkerText());
        if (markerLabelButton != null) markerLabelButton.setMessage(markerLabelText());
        if (areaLabelButton != null) areaLabelButton.setMessage(areaLabelText());
        if (waypointButton != null) waypointButton.setMessage(waypointText());
        if (playerButton != null) playerButton.setMessage(playerText());
        if (playerSearchButton != null) playerSearchButton.setMessage(playerSearchText());
    }

    private void drawWaypoints(DrawContext context, int mapWidth) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (!config.showWaypoints) return;
        double scale = pixelsPerBlock();
        int displaySize = scaledSize(config.waypointSize * 2, 2, 48);
        int centerX = mapWidth / 2;
        int centerY = height / 2;
        context.enableScissor(0, 0, mapWidth, height);
        try {
            for (MinimapConfig.Waypoint waypoint : config.waypoints) {
                int x = centerX + (int) Math.round((waypoint.x - centerWorldX) * scale);
                int y = centerY + (int) Math.round((waypoint.z - centerWorldZ) * scale);
                if (x < -displaySize || x > mapWidth + displaySize
                        || y < -displaySize || y > height + displaySize) continue;
                WaypointPalette.drawMarker(context, x, y, displaySize,
                        waypoint.color, waypoint.shape);
            }
        } finally {
            context.disableScissor();
        }
    }

    private int scaledSize(int base, int minimum, int maximum) {
        double zoomFactor = Math.pow(1.14, 3 - MathHelper.clamp(zoom, 2, 7));
        return MathHelper.clamp((int) Math.round(base * zoomFactor), minimum, maximum);
    }

    private void drawLocalPlayer(DrawContext context, int mapWidth) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        MinimapConfig config = PlanetEarthMinimapClient.config;
        double scale = pixelsPerBlock();
        int x = mapWidth / 2 + (int) Math.round((client.player.getX() - centerWorldX) * scale);
        int y = height / 2 + (int) Math.round((client.player.getZ() - centerWorldZ) * scale);
        int headSize = config.showPlayerFaces
                ? MathHelper.clamp(config.playerFaceSize + 3, 9, 20) : 0;
        if (x < 4 || x >= mapWidth - 4 || y < 4 || y >= height - 4) return;

        PlatformCompat.push(context);
        if (config.showPlayerFaces) {
            int headX = x - headSize / 2;
            int headY = y - headSize / 2;
            context.fill(headX - 3, headY - 3,
                    headX + headSize + 3, headY + headSize + 3, 0xFFFFD84D);
            context.fill(headX - 1, headY - 1,
                    headX + headSize + 1, headY + headSize + 1, 0xFF111111);
            Identifier skin = LiveAtlasPlayerManager.faceTexture(
                    client.player.getName().getString());
            PlatformCompat.push(context);
            PlatformCompat.translate(context, headX, headY);
            if (skin != null) {
                float faceScale = headSize / 16.0f;
                PlatformCompat.scale(context, faceScale, faceScale);
                PlatformCompat.drawTexture(context, skin, 0, 0, 0, 0,
                        16, 16, 16, 16);
            } else {
                context.fill(0, 0, headSize, headSize, 0xFF55FFFF);
            }
            PlatformCompat.pop(context);
        }

        if (config.showPlayerNames) {
            String label = "★ " + client.player.getName().getString() + " (나)";
            float labelScale = config.playerNameScalePercent / 100.0f;
            int rawWidth = client.textRenderer.getWidth(label);
            int labelWidth = (int) Math.ceil(rawWidth * labelScale);
            int labelHeight = (int) Math.ceil(client.textRenderer.fontHeight * labelScale);
            int labelX = x + headSize / 2 + 5;
            if (labelX + labelWidth + 3 > mapWidth) {
                labelX = x - headSize / 2 - labelWidth - 5;
            }
            labelX = MathHelper.clamp(labelX, 3, Math.max(3, mapWidth - labelWidth - 3));
            int labelY = MathHelper.clamp(y - labelHeight / 2,
                    3, Math.max(3, height - labelHeight - 3));
            PlatformCompat.push(context);
            PlatformCompat.translate(context, labelX, labelY);
            PlatformCompat.scale(context, labelScale, labelScale);
            context.fill(-3, -2, rawWidth + 3,
                    client.textRenderer.fontHeight + 2, 0xE0101010);
            context.drawTextWithShadow(client.textRenderer, label, 0, 0, 0xFFFFE45C);
            PlatformCompat.pop(context);
        }
        PlatformCompat.pop(context);
    }

    private void drawPendingWaypoint(DrawContext context, int mapWidth) {
        if (!waypointMenuOpen && !waypointNameOpen) return;
        int x = mapWidth / 2 + (int) Math.round((pendingWaypointX - centerWorldX) * pixelsPerBlock());
        int y = height / 2 + (int) Math.round((pendingWaypointZ - centerWorldZ) * pixelsPerBlock());
        if (x < 0 || x >= mapWidth || y < 0 || y >= height) return;
        WaypointPalette.drawMarker(context, x, y,
                MathHelper.clamp(PlanetEarthMinimapClient.config.waypointSize * 2, 2, 32),
                pendingWaypointColor, pendingWaypointShape);
    }

    private void drawWaypointMenu(DrawContext context, int mapWidth) {
        if (!waypointMenuOpen) return;
        int right = Math.min(waypointMenuX + CONTEXT_WIDTH, mapWidth - 2);
        int bottom = Math.min(waypointMenuY + CONTEXT_HEIGHT, height - 2);
        context.fill(waypointMenuX - 1, waypointMenuY - 1, right + 1, bottom + 1, 0xFF9A9A9A);
        context.fill(waypointMenuX, waypointMenuY, right, bottom, 0xF0202020);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("여기에 웨이포인트 지정"),
                waypointMenuX + (right - waypointMenuX) / 2, waypointMenuY + 7, 0xFFFFFFFF);
    }

    private void drawNavigationStatus(DrawContext context, int mapWidth) {
        String status = NavigationManager.statusLine();
        if (status == null) return;
        String visibleStatus = trimToWidth(status, Math.max(20, mapWidth - 22));
        int textWidth = textRenderer.getWidth(visibleStatus);
        context.fill(6, 20, 14 + textWidth,
                24 + textRenderer.fontHeight, 0xD0101820);
        context.drawTextWithShadow(textRenderer, Text.literal(visibleStatus),
                10, 22, 0xFFFFF3A0);
    }

    private void drawNavigationPanel(DrawContext context, int mapWidth) {
        if (!navigationPanelOpen || navigationTargetName == null) return;
        int right = Math.min(navigationPanelX + NAVIGATION_PANEL_WIDTH, mapWidth - 3);
        int bottom = Math.min(navigationPanelY + NAVIGATION_PANEL_HEIGHT, height - 3);
        context.fill(navigationPanelX - 2, navigationPanelY - 2,
                right + 2, bottom + 2, 0xFF9A9A9A);
        context.fill(navigationPanelX, navigationPanelY, right, bottom, 0xF0181818);
        String name = trimToWidth(navigationTargetName,
                Math.max(20, right - navigationPanelX - 12));
        context.drawTextWithShadow(textRenderer, Text.literal(name),
                navigationPanelX + 6, navigationPanelY + 6, 0xFFFFFFFF);
        String coords = Math.round(navigationTargetX) + ", " + Math.round(navigationTargetZ);
        context.drawTextWithShadow(textRenderer, Text.literal(coords),
                navigationPanelX + 6, navigationPanelY + 18, 0xFFBFBFBF);

        double tolerance = navigationSelectionTolerance();
        boolean deleteVia = NavigationManager.isViaPoint(
                navigationTargetX, navigationTargetZ, tolerance);
        boolean cancel = !deleteVia && NavigationManager.isDestination(
                navigationTargetX, navigationTargetZ, tolerance);
        boolean addVia = !deleteVia && !cancel && NavigationManager.isActive();
        int buttonLeft = navigationPanelX + 6;
        int buttonTop = navigationPanelY + 32;
        int buttonRight = right - 6;
        int buttonBottom = Math.min(bottom - 5, buttonTop + 20);
        context.fill(buttonLeft, buttonTop, buttonRight, buttonBottom, 0xFF777777);
        context.fill(buttonLeft + 1, buttonTop + 1,
                buttonRight - 1, buttonBottom - 1, 0xFF3A3A3A);
        Text buttonText = Text.literal(deleteVia ? "경유지 삭제"
                : cancel ? "길안내 취소" : addVia ? "경유지 추가" : "길안내 시작");
        context.drawCenteredTextWithShadow(textRenderer, buttonText,
                buttonLeft + (buttonRight - buttonLeft) / 2, buttonTop + 6,
                deleteVia || cancel ? 0xFFFF9A9A
                        : addVia ? 0xFFFFE39A : 0xFFB8FFB8);
    }

    private String trimToWidth(String text, int maximumWidth) {
        if (textRenderer.getWidth(text) <= maximumWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0
                && textRenderer.getWidth(text.substring(0, end) + suffix) > maximumWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private void drawWaypointNamePanel(DrawContext context) {
        if (!waypointNameOpen) return;
        context.fill(waypointNamePanelX - 2, waypointNamePanelY - 2,
                waypointNamePanelX + WAYPOINT_PANEL_WIDTH + 2,
                waypointNamePanelY + WAYPOINT_PANEL_HEIGHT + 2, 0xFF8F8F8F);
        context.fill(waypointNamePanelX, waypointNamePanelY,
                waypointNamePanelX + WAYPOINT_PANEL_WIDTH,
                waypointNamePanelY + WAYPOINT_PANEL_HEIGHT, 0xF0181818);
        context.drawTextWithShadow(textRenderer, Text.literal("웨이포인트 이름 설정"),
                waypointNamePanelX + 7, waypointNamePanelY + 6, 0xFFFFD95A);
        context.drawTextWithShadow(textRenderer, Text.literal("색상"),
                waypointNamePanelX + 7, waypointNamePanelY + 44, 0xFFBFBFBF);
        int swatchY = waypointNamePanelY + 56;
        for (int index = 0; index < WaypointPalette.COLORS.length; index++) {
            int swatchX = waypointNamePanelX + 7 + index * COLOR_SWATCH_STEP;
            int color = WaypointPalette.COLORS[index];
            if (color == pendingWaypointColor) {
                context.fill(swatchX - 2, swatchY - 2,
                        swatchX + COLOR_SWATCH_SIZE + 2,
                        swatchY + COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
                context.fill(swatchX - 1, swatchY - 1,
                        swatchX + COLOR_SWATCH_SIZE + 1,
                        swatchY + COLOR_SWATCH_SIZE + 1, 0xFF101010);
            }
            context.fill(swatchX, swatchY,
                    swatchX + COLOR_SWATCH_SIZE, swatchY + COLOR_SWATCH_SIZE,
                    0xFF000000 | color);
        }
    }

    private void openWaypointNameEditor() {
        waypointMenuOpen = false;
        waypointNameOpen = true;
        int mapWidth = mapWidth();
        int markerX = mapWidth / 2 + (int) Math.round((pendingWaypointX - centerWorldX) * pixelsPerBlock());
        int markerY = height / 2 + (int) Math.round((pendingWaypointZ - centerWorldZ) * pixelsPerBlock());
        waypointNamePanelX = MathHelper.clamp(markerX - WAYPOINT_PANEL_WIDTH / 2,
                3, Math.max(3, mapWidth - WAYPOINT_PANEL_WIDTH - 3));
        int above = markerY - WAYPOINT_PANEL_HEIGHT - 8;
        waypointNamePanelY = above >= 3 ? above
                : Math.min(height - WAYPOINT_PANEL_HEIGHT - 3, markerY + 8);
        pendingWaypointColor = WaypointPalette.colorForIndex(
                PlanetEarthMinimapClient.config.waypoints.size());
        pendingWaypointShape = WaypointPalette.normalizeShape(
                PlanetEarthMinimapClient.config.waypointShape);

        String defaultName = "웨이포인트 " + (PlanetEarthMinimapClient.config.waypoints.size() + 1);
        waypointNameField = new TextFieldWidget(textRenderer,
                waypointNamePanelX + 6, waypointNamePanelY + 19,
                WAYPOINT_PANEL_WIDTH - 12, 20, Text.literal("웨이포인트 이름"));
        waypointNameField.setMaxLength(40);
        waypointNameField.setText(defaultName);
        waypointNameField.setSelectionStart(0);
        waypointNameField.setSelectionEnd(defaultName.length());
        addDrawableChild(waypointNameField);
        addDrawableChild(ButtonWidget.builder(Text.literal("저장"), pressed -> savePendingWaypoint())
                .dimensions(waypointNamePanelX + 6, waypointNamePanelY + 80, 87, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("취소"), pressed -> cancelWaypointNaming())
                .dimensions(waypointNamePanelX + 97, waypointNamePanelY + 80, 87, 20).build());
        setFocused(waypointNameField);
        waypointNameField.setFocused(true);
    }

    private void savePendingWaypoint() {
        if (!waypointNameOpen || waypointNameField == null) return;
        String name = waypointNameField.getText();
        waypointNameOpen = false;
        pendingWaypointShape = WaypointPalette.SHAPE_PIN;
        PlanetEarthMinimapClient.config.waypointShape = WaypointPalette.SHAPE_PIN;
        addWaypoint(name, pendingWaypointX, pendingWaypointZ,
                pendingWaypointColor, pendingWaypointShape);
    }

    private void cancelWaypointNaming() {
        waypointNameOpen = false;
        waypointNameField = null;
        rebuildSidebar();
    }

    private void drawAreaPopup(DrawContext context, int mapWidth) {
        if (selectedArea == null) return;
        int popupWidth = Math.min(240, mapWidth - 12);
        List<OrderedText> details = textRenderer.wrapLines(Text.literal(selectedArea.details()), popupWidth - 16);
        int shownLines = Math.min(3, details.size());
        int popupHeight = 42 + shownLines * (textRenderer.fontHeight + 1);
        int x = MathHelper.clamp(areaPopupX, 4, Math.max(4, mapWidth - popupWidth - 4));
        int y = MathHelper.clamp(areaPopupY, 20, Math.max(20, height - popupHeight - 18));
        context.fill(x - 1, y - 1, x + popupWidth + 1, y + popupHeight + 1, 0xFF9A9A9A);
        context.fill(x, y, x + popupWidth, y + popupHeight, 0xED151515);
        context.drawTextWithShadow(textRenderer, Text.literal("영역 정보 · " + selectedArea.category()),
                x + 7, y + 6, 0xFFFFD95A);
        context.drawTextWithShadow(textRenderer, Text.literal("국가: " + selectedArea.country()),
                x + 7, y + 17, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("마을: " + selectedArea.town()),
                x + 7, y + 28, 0xFFFFFFFF);
        int lineY = y + 40;
        for (int i = 0; i < shownLines; i++) {
            context.drawTextWithShadow(textRenderer, details.get(i), x + 7, lineY, 0xFFBFBFBF);
            lineY += textRenderer.fontHeight + 1;
        }
    }

    protected final boolean interceptMouseClicked(double mouseX, double mouseY, int button) {
        int mapWidth = mapWidth();
        if (button == 0 && waypointNameOpen) {
            int swatchY = waypointNamePanelY + 56;
            if (mouseY >= swatchY - 2 && mouseY <= swatchY + COLOR_SWATCH_SIZE + 2) {
                for (int index = 0; index < WaypointPalette.COLORS.length; index++) {
                    int swatchX = waypointNamePanelX + 7 + index * COLOR_SWATCH_STEP;
                    if (mouseX >= swatchX - 2
                            && mouseX <= swatchX + COLOR_SWATCH_SIZE + 2) {
                        pendingWaypointColor = WaypointPalette.COLORS[index];
                        return true;
                    }
                }
            }
        }
        if (button == 0 && navigationPanelOpen) {
            int right = Math.min(navigationPanelX + NAVIGATION_PANEL_WIDTH, mapWidth - 3);
            int bottom = Math.min(navigationPanelY + NAVIGATION_PANEL_HEIGHT, height - 3);
            int buttonLeft = navigationPanelX + 6;
            int buttonTop = navigationPanelY + 32;
            int buttonRight = right - 6;
            int buttonBottom = Math.min(bottom - 5, buttonTop + 20);
            if (mouseX >= buttonLeft && mouseX <= buttonRight
                    && mouseY >= buttonTop && mouseY <= buttonBottom) {
                MinimapEditorScreen.playControlSound();
                double tolerance = navigationSelectionTolerance();
                if (NavigationManager.isViaPoint(
                        navigationTargetX, navigationTargetZ, tolerance)) {
                    NavigationManager.removeVia(
                            navigationTargetX, navigationTargetZ, tolerance);
                } else if (NavigationManager.isDestination(
                        navigationTargetX, navigationTargetZ, tolerance)) {
                    NavigationManager.cancel();
                } else if (NavigationManager.isActive()) {
                    NavigationManager.addVia(navigationTargetName,
                            navigationTargetX, navigationTargetZ);
                } else {
                    NavigationManager.start(navigationTargetName,
                            navigationTargetX, navigationTargetZ);
                }
                navigationPanelOpen = false;
                return true;
            }
            navigationPanelOpen = false;
        }
        if (button == 1 && mouseX < mapWidth) {
            if (waypointNameOpen) {
                waypointNameOpen = false;
                rebuildSidebar();
            }
            pendingWaypointX = centerWorldX + (mouseX - mapWidth / 2.0) / pixelsPerBlock();
            pendingWaypointZ = centerWorldZ + (mouseY - height / 2.0) / pixelsPerBlock();
            pendingWaypointColor = WaypointPalette.colorForIndex(
                    PlanetEarthMinimapClient.config.waypoints.size());
            pendingWaypointShape = WaypointPalette.normalizeShape(
                    PlanetEarthMinimapClient.config.waypointShape);
            waypointMenuX = MathHelper.clamp((int) mouseX - CONTEXT_WIDTH / 2,
                    2, Math.max(2, mapWidth - CONTEXT_WIDTH - 2));
            int desiredY = (int) mouseY - CONTEXT_HEIGHT - 7;
            waypointMenuY = desiredY < 2 ? (int) mouseY + 7 : desiredY;
            waypointMenuOpen = true;
            navigationPanelOpen = false;
            selectedArea = null;
            return true;
        }

        if (button == 0 && waypointMenuOpen) {
            if (mouseX >= waypointMenuX && mouseX <= waypointMenuX + CONTEXT_WIDTH
                    && mouseY >= waypointMenuY && mouseY <= waypointMenuY + CONTEXT_HEIGHT) {
                openWaypointNameEditor();
                return true;
            }
            waypointMenuOpen = false;
        }

        return false;
    }

    protected final boolean handleMouseClickedAfterChildren(
            double mouseX, double mouseY, int button, boolean childHandled) {
        int mapWidth = mapWidth();
        if (waypointNameOpen) {
            return true;
        }

        if (childHandled) return true;
        if (mouseX >= mapWidth) return false;
        if (button == 0) {
            double worldX = centerWorldX + (mouseX - mapWidth / 2.0) / pixelsPerBlock();
            double worldZ = centerWorldZ + (mouseY - height / 2.0) / pixelsPerBlock();
            selectedArea = LiveAtlasMarkerManager.findArea(worldX, worldZ, enabledCategories);
            areaPopupX = (int) mouseX + 10;
            areaPopupY = (int) mouseY + 8;
            panning = true;
            mapClickCandidate = true;
            mapDragged = false;
            mapPressX = mouseX;
            mapPressY = mouseY;
            return true;
        }
        return false;
    }

    /** Uses the selected waypoint shape's real screen rectangle. */
    private MinimapConfig.Waypoint waypointAtScreen(double mouseX, double mouseY) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (!config.showWaypoints || config.waypoints.isEmpty()) return null;
        int mapWidth = mapWidth();
        int displaySize = scaledSize(config.waypointSize * 2, 2, 48);
        double scale = pixelsPerBlock();
        double bestScore = Double.POSITIVE_INFINITY;
        MinimapConfig.Waypoint best = null;
        for (MinimapConfig.Waypoint waypoint : config.waypoints) {
            int pinX = mapWidth / 2
                    + (int) Math.round((waypoint.x - centerWorldX) * scale);
            int pinY = height / 2
                    + (int) Math.round((waypoint.z - centerWorldZ) * scale);
            if (!WaypointPalette.hitMarker(mouseX, mouseY, pinX, pinY,
                    displaySize, waypoint.shape)) continue;
            double dx = mouseX - pinX;
            double dy = mouseY - WaypointPalette.markerCenterY(
                    pinY, displaySize, waypoint.shape);
            double score = dx * dx + dy * dy;
            if (score < bestScore) {
                bestScore = score;
                best = waypoint;
            }
        }
        return best;
    }

    private void openNavigationSelection(double mouseX, double mouseY) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int mapWidth = mapWidth();
        double scale = pixelsPerBlock();
        double worldX = centerWorldX + (mouseX - mapWidth / 2.0) / scale;
        double worldZ = centerWorldZ + (mouseY - height / 2.0) / scale;
        double tolerance = navigationSelectionTolerance();
        NavigationManager.RoutePoint routePoint = NavigationManager.findViaPoint(
                worldX, worldZ, tolerance);
        if (routePoint == null) {
            routePoint = NavigationManager.findDestination(worldX, worldZ, tolerance);
        }
        MinimapConfig.Waypoint waypoint = routePoint == null
                ? waypointAtScreen(mouseX, mouseY) : null;
        if (routePoint != null) {
            navigationTargetName = routePoint.name();
            navigationTargetX = routePoint.x();
            navigationTargetZ = routePoint.z();
        } else if (waypoint != null) {
            navigationTargetName = waypoint.name == null || waypoint.name.isBlank()
                    ? "웨이포인트" : waypoint.name;
            navigationTargetX = waypoint.x;
            navigationTargetZ = waypoint.z;
        } else {
            LiveAtlasMarkerManager.MarkerEntry marker = null;
            if (config.showSiteMarkers) {
                double markerRadius = Math.max(5.0,
                        scaledSize(config.siteMarkerSize, 5, 30) / scale);
                marker = LiveAtlasMarkerManager.findMarker(
                        worldX, worldZ, markerRadius, enabledCategories);
            }
            if (marker != null) {
                navigationTargetName = marker.name();
                navigationTargetX = marker.x();
                navigationTargetZ = marker.z();
            } else {
                navigationTargetName = "선택 지점 "
                        + Math.round(worldX) + ", " + Math.round(worldZ);
                navigationTargetX = worldX;
                navigationTargetZ = worldZ;
            }
        }

        navigationPanelX = MathHelper.clamp((int) mouseX - NAVIGATION_PANEL_WIDTH / 2,
                3, Math.max(3, mapWidth - NAVIGATION_PANEL_WIDTH - 3));
        int above = (int) mouseY - NAVIGATION_PANEL_HEIGHT - 8;
        navigationPanelY = above >= 36 ? above
                : Math.min(height - NAVIGATION_PANEL_HEIGHT - 3, (int) mouseY + 8);
        navigationPanelOpen = true;
        waypointMenuOpen = false;
    }

    private double navigationSelectionTolerance() {
        return Math.max(3.0, 6.0 / pixelsPerBlock());
    }

    protected final boolean handleMouseDragged(
            double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (waypointNameOpen) return false;
        if (panning && button == 0) {
            if (Math.abs(mouseX - mapPressX) > 3.0 || Math.abs(mouseY - mapPressY) > 3.0) {
                mapDragged = true;
                navigationPanelOpen = false;
            }
            centerWorldX -= deltaX / pixelsPerBlock();
            centerWorldZ -= deltaY / pixelsPerBlock();
            selectedArea = null;
            waypointMenuOpen = false;
            return true;
        }
        return false;
    }

    protected final void handleMouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && panning && mapClickCandidate && !mapDragged
                && mouseX >= 0 && mouseX < mapWidth() && mouseY >= 0 && mouseY < height) {
            openNavigationSelection(mouseX, mouseY);
        }
        panning = false;
        mapClickCandidate = false;
        mapDragged = false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return handleMouseScrolled(mouseX, mouseY, amount);
    }

    // Minecraft 1.20.2+ added a horizontal scroll component. Keeping both
    // overloads lets the same source compile on both sides of that API change.
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        return handleMouseScrolled(mouseX, mouseY, verticalAmount);
    }

    private boolean handleMouseScrolled(double mouseX, double mouseY, double amount) {
        if (waypointNameOpen) return true;
        int mapWidth = mapWidth();
        if (mouseX >= mapWidth && playerBrowserOpen) {
            List<LiveAtlasPlayerManager.PlayerEntry> players = filteredPlayerEntries(
                    LiveAtlasPlayerManager.playerEntries());
            int visibleRows = Math.max(1, (height - 90) / 22);
            int maxScroll = Math.max(0, players.size() - visibleRows);
            int previous = markerScroll;
            markerScroll = MathHelper.clamp(markerScroll + (amount < 0 ? 3 : -3), 0, maxScroll);
            if (markerScroll != previous) {
                rebuildPlayerResults(width - SIDEBAR_WIDTH + 8, SIDEBAR_WIDTH - 16);
            }
            return true;
        }
        if (mouseX >= mapWidth && openedCategory != null) {
            List<LiveAtlasMarkerManager.MarkerEntry> markers = filteredMarkerEntries(
                    LiveAtlasMarkerManager.markerEntries(openedCategory));
            int visibleRows = Math.max(1, (height - 90) / 22);
            int maxScroll = Math.max(0, markers.size() - visibleRows);
            int previous = markerScroll;
            markerScroll = MathHelper.clamp(markerScroll + (amount < 0 ? 3 : -3), 0, maxScroll);
            if (markerScroll != previous) {
                rebuildMarkerResults(width - SIDEBAR_WIDTH + 8, SIDEBAR_WIDTH - 16);
            }
            return true;
        }
        // The default sidebar (categories, settings, waypoint list) — scrolling here
        // used to do nothing, which was how a "삭제" button below the fold could end up
        // permanently unreachable.
        if (mouseX >= mapWidth) {
            int previousScroll = sidebarScroll;
            sidebarScroll = MathHelper.clamp(sidebarScroll + (amount < 0 ? 22 : -22),
                    0, sidebarMaxScroll);
            if (sidebarScroll != previousScroll) {
                rebuildSidebar();
            }
            return true;
        }
        double beforeScale = pixelsPerBlock();
        double worldUnderMouseX = centerWorldX + (mouseX - mapWidth / 2.0) / beforeScale;
        double worldUnderMouseZ = centerWorldZ + (mouseY - height / 2.0) / beforeScale;
        // Minimum raised from 0: fully zoomed in showed far less than a screenful of
        // useful map for how disorienting it was, and wasn't needed in practice.
        zoom = MathHelper.clamp(zoom + (amount > 0 ? -1 : 1), 2, 7);
        double afterScale = pixelsPerBlock();
        centerWorldX = worldUnderMouseX - (mouseX - mapWidth / 2.0) / afterScale;
        centerWorldZ = worldUnderMouseZ - (mouseY - height / 2.0) / afterScale;
        selectedArea = null;
        waypointMenuOpen = false;
        navigationPanelOpen = false;
        return true;
    }

    protected final boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (waypointNameOpen) {
            if (keyCode == 257 || keyCode == 335) {
                savePendingWaypoint();
                return true;
            }
            if (keyCode == 256) {
                cancelWaypointNaming();
                return true;
            }
        }
        return false;
    }

    protected final boolean isSearchInputFocused() {
        return markerSearchField != null && markerSearchField.isFocused();
    }

    private int mapWidth() {
        return Math.max(1, width - SIDEBAR_WIDTH);
    }

    private double pixelsPerBlock() {
        return 4.0 / (1 << zoom);
    }

    void addWaypoint(String name, double x, double z) {
        addWaypoint(name, x, z,
                WaypointPalette.colorForIndex(PlanetEarthMinimapClient.config.waypoints.size()),
                PlanetEarthMinimapClient.config.waypointShape);
    }

    void addWaypoint(String name, double x, double z, int color) {
        addWaypoint(name, x, z, color, PlanetEarthMinimapClient.config.waypointShape);
    }

    void addWaypoint(String name, double x, double z, int color, String shape) {
        String finalName = name == null || name.isBlank()
                ? "웨이포인트 " + (PlanetEarthMinimapClient.config.waypoints.size() + 1)
                : name.trim();
        double anchorY = client != null && client.player != null
                ? client.player.getY()
                : MinimapConfig.UNKNOWN_WAYPOINT_Y;
        PlanetEarthMinimapClient.config.waypoints.add(
                new MinimapConfig.Waypoint(finalName, x, anchorY, z, color, shape));
        PlanetEarthMinimapClient.config.save();
        rebuildSidebar();
    }

    @Override
    public void close() {
        PlanetEarthMinimapClient.config.save();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final class IntSlider extends SliderWidget {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer setter;

        private IntSlider(int x, int y, int width, String label,
                          int min, int max, int current, IntConsumer setter) {
            super(x, y, width, 20, Text.empty(), (current - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int currentValue() {
            return MathHelper.clamp(min + (int) Math.round(value * (max - min)), min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + currentValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(currentValue());
            updateMessage();
        }
    }
}
