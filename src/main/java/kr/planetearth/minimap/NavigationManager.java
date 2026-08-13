package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Straight-line HUD guidance to a selected LiveAtlas marker or local waypoint. */
public final class NavigationManager {
    private static final double ARRIVAL_DISTANCE = 5.0;
    private static Target destination;
    private static final List<Target> viaPoints = new ArrayList<>();

    private NavigationManager() {}

    public static void start(String name, double x, double z) {
        String safeName = name == null || name.isBlank() ? "목표" : name.trim();
        viaPoints.clear();
        destination = new Target(safeName, x, z);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("길 안내 시작: " + safeName), true);
        }
    }

    public static void cancel() {
        Target active = destination;
        if (active == null) return;
        destination = null;
        viaPoints.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("길 안내 취소: " + active.name), true);
        }
    }

    /** Inserts the newest stop first while preserving the final destination. */
    public static void addVia(String name, double x, double z) {
        Target finalDestination = destination;
        if (finalDestination == null) {
            start(name, x, z);
            return;
        }
        String safeName = name == null || name.isBlank() ? "경유지" : name.trim();
        // The most recently added via point is always visited first.
        viaPoints.add(0, new Target(safeName, x, z));
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "경유지 추가: " + safeName + " · 최종 목적지 " + finalDestination.name), true);
        }
    }

    public static void removeVia(double x, double z, double tolerance) {
        double safeTolerance = Math.max(1.0, tolerance);
        for (int index = 0; index < viaPoints.size(); index++) {
            Target via = viaPoints.get(index);
            if (!samePoint(via, x, z, safeTolerance)) continue;
            viaPoints.remove(index);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("경유지 삭제: " + via.name), true);
            }
            return;
        }
    }

    public static boolean isActive() {
        return destination != null;
    }

    public static boolean isDestination(double x, double z, double tolerance) {
        return destination != null
                && samePoint(destination, x, z, Math.max(1.0, tolerance));
    }

    public static boolean isViaPoint(double x, double z, double tolerance) {
        return findViaPoint(x, z, tolerance) != null;
    }

    public static RoutePoint findViaPoint(double x, double z, double tolerance) {
        double safeTolerance = Math.max(1.0, tolerance);
        Target best = null;
        double bestDistanceSquared = safeTolerance * safeTolerance;
        for (Target via : viaPoints) {
            double dx = via.x - x;
            double dz = via.z - z;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = via;
            }
        }
        return best == null ? null : new RoutePoint(best.name, best.x, best.z);
    }

    public static RoutePoint findDestination(double x, double z, double tolerance) {
        Target active = destination;
        if (active == null || !samePoint(active, x, z, Math.max(1.0, tolerance))) return null;
        return new RoutePoint(active.name, active.x, active.z);
    }

    public static String statusLine() {
        Target active = destination;
        MinecraftClient client = MinecraftClient.getInstance();
        if (active == null || client.player == null) return null;
        double dx = active.x - client.player.getX();
        double dz = active.z - client.player.getZ();
        Target next = currentTarget();
        return "최종 목적지: " + active.name
                + "  |  " + Math.round(active.x) + ", " + Math.round(active.z)
                + "  |  " + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m"
                + (viaPoints.isEmpty() ? ""
                : "  |  경유지 " + viaPoints.size() + "개 · 다음 " + next.name);
    }

    public static void tick(MinecraftClient client) {
        Target active = currentTarget();
        if (active == null || client.player == null || client.world == null) return;
        double dx = active.x - client.player.getX();
        double dz = active.z - client.player.getZ();
        if (dx * dx + dz * dz > ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) return;

        if (!viaPoints.isEmpty() && active == viaPoints.get(0)) {
            viaPoints.remove(0);
            Target next = currentTarget();
            client.player.sendMessage(Text.literal(
                    "경유지 도착: " + active.name + " · 다음: " + next.name), true);
            return;
        }
        destination = null;
        viaPoints.clear();
        client.getSoundManager().play(PlatformCompat.navigationCompleteSound(1.0f, 0.8f));
        client.player.sendMessage(Text.literal("도착: " + active.name + " · 길 안내 종료"), true);
    }

    public static void renderOnMinimap(DrawContext context, int mapX, int mapY,
                                        int width, int height, double playerX,
                                        double playerZ, int zoom) {
        Target active = currentTarget();
        if (active == null) return;
        boolean showingVia = !viaPoints.isEmpty();
        double scale = 4.0 / (1 << Math.max(0, Math.min(zoom, 7)));
        double pixelX = (active.x - playerX) * scale;
        double pixelY = (active.z - playerZ) * scale;
        double fullLength = Math.sqrt(pixelX * pixelX + pixelY * pixelY);
        if (fullLength < 0.001) return;

        int centerX = mapX + width / 2;
        int centerY = mapY + height / 2;
        double limitX = Math.max(3, width / 2.0 - 7);
        double limitY = Math.max(3, height / 2.0 - 7);
        double clip = Math.min(1.0, Math.min(limitX / Math.max(0.001, Math.abs(pixelX)),
                limitY / Math.max(0.001, Math.abs(pixelY))));
        double endX = centerX + pixelX * clip;
        double endY = centerY + pixelY * clip;
        double shownLength = fullLength * clip;

        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        for (double distance = 5.0; distance < shownLength; distance += 8.0) {
            double ratio = distance / fullLength;
            int dotX = (int) Math.round(centerX + pixelX * ratio);
            int dotY = (int) Math.round(centerY + pixelY * ratio);
            context.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, 0xF0FFFFFF);
        }
        int targetX = (int) Math.round(endX);
        int targetY = (int) Math.round(endY);
        context.fill(targetX - 4, targetY - 4, targetX + 5, targetY + 5, 0xE0101010);
        context.fill(targetX - 2, targetY - 2, targetX + 3, targetY + 3,
                showingVia ? 0xFFFF9F43 : 0xFFFFE45C);
        context.disableScissor();

        MinecraftClient client = MinecraftClient.getInstance();
        double worldDistance = Math.sqrt((active.x - playerX) * (active.x - playerX)
                + (active.z - playerZ) * (active.z - playerZ));
        String label = (showingVia ? "경유지: " : "목적지: ")
                + active.name + " · " + Math.round(worldDistance) + "m";
        int textWidth = client.textRenderer.getWidth(label);
        int labelX = MathHelper.clamp(targetX - textWidth / 2,
                mapX + 3, Math.max(mapX + 3, mapX + width - textWidth - 3));
        int labelY = targetY - client.textRenderer.fontHeight - 8;
        if (labelY < mapY + 3) labelY = targetY + 7;
        labelY = MathHelper.clamp(labelY, mapY + 3,
                Math.max(mapY + 3, mapY + height - client.textRenderer.fontHeight - 3));
        context.fill(labelX - 2, labelY - 1, labelX + textWidth + 2,
                labelY + client.textRenderer.fontHeight + 1, 0xC0101010);
        context.drawTextWithShadow(client.textRenderer, Text.literal(label),
                labelX, labelY, 0xFFFFF3A0);
    }

    /** Shows the active route immediately while the full map remains open. */
    public static void renderOnFullMap(DrawContext context, int mapX, int mapY,
                                       int width, int height, double centerWorldX,
                                       double centerWorldZ, double scale) {
        Target finalDestination = destination;
        MinecraftClient client = MinecraftClient.getInstance();
        if (finalDestination == null || client.player == null) return;

        List<Target> route = new ArrayList<>(viaPoints);
        route.add(finalDestination);

        double startX = mapX + width / 2.0
                + (client.player.getX() - centerWorldX) * scale;
        double startY = mapY + height / 2.0
                + (client.player.getZ() - centerWorldZ) * scale;
        int minX = mapX + 4;
        int minY = mapY + 4;
        int maxX = mapX + width - 5;
        int maxY = mapY + height - 5;

        context.enableScissor(mapX, mapY, mapX + width, mapY + height);
        try {
            for (int index = 0; index < route.size(); index++) {
                Target point = route.get(index);
                double targetX = mapX + width / 2.0 + (point.x - centerWorldX) * scale;
                double targetY = mapY + height / 2.0 + (point.z - centerWorldZ) * scale;
                drawDottedLeg(context, startX, startY, targetX, targetY,
                        minX, minY, maxX, maxY);
                boolean finalPoint = index == route.size() - 1;
                if (targetX >= minX && targetX <= maxX && targetY >= minY && targetY <= maxY) {
                    drawRoutePoint(context, client, (int) Math.round(targetX),
                            (int) Math.round(targetY), point,
                            finalPoint ? 0 : index + 1, mapX, mapY, width, height);
                }
                startX = targetX;
                startY = targetY;
            }
        } finally {
            context.disableScissor();
        }
    }

    private static void drawDottedLeg(DrawContext context,
                                      double startX, double startY,
                                      double targetX, double targetY,
                                      int minX, int minY, int maxX, int maxY) {
        double dx = targetX - startX;
        double dy = targetY - startY;
        double fullLength = Math.sqrt(dx * dx + dy * dy);
        if (fullLength < 0.001) return;
        double[] range = {0.0, 1.0};
        if (!clip(-dx, startX - minX, range)
                || !clip(dx, maxX - startX, range)
                || !clip(-dy, startY - minY, range)
                || !clip(dy, maxY - startY, range)) return;
        double shownStart = fullLength * range[0];
        double shownEnd = fullLength * range[1];
        double firstDot = Math.ceil(shownStart / 9.0) * 9.0;
        for (double distance = firstDot; distance <= shownEnd; distance += 9.0) {
            double ratio = distance / fullLength;
            int dotX = (int) Math.round(startX + dx * ratio);
            int dotY = (int) Math.round(startY + dy * ratio);
            context.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, 0xF0FFFFFF);
        }
    }

    private static void drawRoutePoint(DrawContext context, MinecraftClient client,
                                       int x, int y, Target point, int viaNumber,
                                       int mapX, int mapY, int width, int height) {
        boolean via = viaNumber > 0;
        context.fill(x - 5, y - 5, x + 6, y + 6, 0xE0101010);
        if (via) {
            context.fill(x - 1, y - 4, x + 2, y + 5, 0xFFFF9F43);
            context.fill(x - 4, y - 1, x + 5, y + 2, 0xFFFF9F43);
        } else {
            context.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFE45C);
        }
        String label = via ? "경유지 " + viaNumber + " · " + point.name
                : "최종 목적지 · " + point.name;
        int textWidth = client.textRenderer.getWidth(label);
        int labelX = MathHelper.clamp(x - textWidth / 2,
                mapX + 3, Math.max(mapX + 3, mapX + width - textWidth - 3));
        int labelY = y - client.textRenderer.fontHeight - 9;
        if (labelY < mapY + 3) labelY = y + 8;
        labelY = MathHelper.clamp(labelY, mapY + 3,
                Math.max(mapY + 3, mapY + height - client.textRenderer.fontHeight - 3));
        context.fill(labelX - 2, labelY - 1, labelX + textWidth + 2,
                labelY + client.textRenderer.fontHeight + 1, 0xC0101010);
        context.drawTextWithShadow(client.textRenderer, Text.literal(label),
                labelX, labelY, via ? 0xFFFFC37A : 0xFFFFF3A0);
    }

    private static boolean clip(double direction, double offset, double[] range) {
        if (Math.abs(direction) < 0.000001) return offset >= 0.0;
        double ratio = offset / direction;
        if (direction < 0.0) {
            if (ratio > range[1]) return false;
            if (ratio > range[0]) range[0] = ratio;
        } else {
            if (ratio < range[0]) return false;
            if (ratio < range[1]) range[1] = ratio;
        }
        return true;
    }

    private static Target currentTarget() {
        if (!viaPoints.isEmpty()) return viaPoints.get(0);
        return destination;
    }

    private static boolean samePoint(Target point, double x, double z, double tolerance) {
        double dx = point.x - x;
        double dz = point.z - z;
        return dx * dx + dz * dz <= tolerance * tolerance;
    }

    public record RoutePoint(String name, double x, double z) {}

    private record Target(String name, double x, double z) {}
}
