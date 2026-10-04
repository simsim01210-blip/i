package kr.planetearth.minimap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** State and entry points for fetching the web map through the in-game browser.
 *  Deliberately free of any MCEF/JCEF types: this class is loaded whether or not
 *  the MCEF mod is installed, and every call into {@link McefBridge} (which does use
 *  those types) is guarded by {@link #MCEF_PRESENT}, so without MCEF the bridge
 *  class is simply never loaded. */
final class WebMapBrowser {
    private static final boolean MCEF_PRESENT = FabricLoader.getInstance().isModLoaded("mcef");

    private static volatile boolean challengeSeen;
    private static volatile boolean verificationNeeded;
    private static volatile boolean announcedMissingMcef;
    private static volatile boolean announcedVerification;

    private WebMapBrowser() {}

    static boolean mcefInstalled() {
        return MCEF_PRESENT;
    }

    /** True once a plain request has been turned away by the Cloudflare check. */
    static boolean challengeSeen() {
        return challengeSeen;
    }

    static boolean verificationNeeded() {
        return verificationNeeded;
    }

    /** Whether requests should go through the in-game browser right now. */
    static boolean isRouting() {
        return challengeSeen && MCEF_PRESENT && McefBridge.isAvailable();
    }

    static void onChallengeDetected() {
        if (challengeSeen) return;
        challengeSeen = true;
        PlanetEarthMinimapClient.LOGGER.warn("웹지도가 Cloudflare 확인 페이지로 요청을 막고 있습니다"
                + (MCEF_PRESENT ? " — 게임 내 브라우저로 전환합니다" : " — MCEF 모드가 없어 지도를 받을 수 없습니다"));
        if (!MCEF_PRESENT && !announcedMissingMcef) {
            announcedMissingMcef = true;
            announce("웹지도가 Cloudflare 인증으로 막혀 있어요. MCEF 모드를 설치하면 "
                    + "게임 안에서 인증하고 지도를 볼 수 있습니다.");
        }
    }

    static CompletableFuture<WebMapFetcher.Response> fetch(String url, Map<String, String> headers,
                                                           Duration timeout) {
        return McefBridge.fetch(url, headers, timeout);
    }

    /** The browser itself was shown the check: only the player can get past it. */
    static void onBrowserChallenge() {
        if (!verificationNeeded) {
            PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 인증 필요 상태로 전환");
        }
        verificationNeeded = true;
        if (!announcedVerification) {
            announcedVerification = true;
            announce("웹지도 인증이 필요해요. " + fullMapKeyName()
                    + " 키로 전체 지도를 열고 '웹지도 인증' 버튼을 눌러주세요.");
        }
    }

    /** A request through the browser came back normally (or the player just passed
     *  the check): anything that failed meanwhile is retried right away. */
    static void onBrowserSuccess() {
        if (!verificationNeeded) return;
        verificationNeeded = false;
        PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 인증 통과 — 지도를 다시 불러옵니다");
        announcedVerification = false;
        LiveAtlasTileManager.onWebMapRecovered();
    }

    static void openVerifyScreen() {
        if (!canVerify()) return;
        McefBridge.openVerifyScreen();
    }

    /** Render thread, once per frame: hands queued requests to the browser. */
    static void pump() {
        if (!MCEF_PRESENT || !challengeSeen) return;
        McefBridge.pump();
    }

    /** Whatever key the player actually bound the full map to (N by default). */
    static String fullMapKeyName() {
        if (PlanetEarthMinimapClient.fullMapKey == null) return "N";
        return PlanetEarthMinimapClient.fullMapKey.getBoundKeyLocalizedText().getString();
    }

    /** Whether the player can open the verification window right now — offered as
     *  soon as the block is seen, not only once a browser request has hit the check,
     *  so the button is there even if those requests never come back at all. */
    static boolean canVerify() {
        return MCEF_PRESENT && challengeSeen && McefBridge.isAvailable();
    }

    private static final long JOIN_NOTICE_DELAY_MILLIS = 4_000L;
    private static final long JOIN_NOTICE_WINDOW_MILLIS = 30_000L;
    private static long joinedAtMillis;

    static void onJoin() {
        joinedAtMillis = System.currentTimeMillis();
    }

    /** Client tick: once per join, a highlighted chat line explaining the block and
     *  how to verify — sent as soon as the block is detected (it takes the first map
     *  request after joining to find out), and not at all if the map just works. */
    static void tickJoinNotice(MinecraftClient client) {
        if (joinedAtMillis == 0L || client.player == null) return;
        long sinceJoin = System.currentTimeMillis() - joinedAtMillis;
        if (sinceJoin < JOIN_NOTICE_DELAY_MILLIS) return;
        if (sinceJoin > JOIN_NOTICE_WINDOW_MILLIS) {
            joinedAtMillis = 0L;
            return;
        }
        if (!challengeSeen) return;
        joinedAtMillis = 0L;
        if (MCEF_PRESENT) {
            announce("웹지도가 Cloudflare 인증으로 막혀 있어요. " + fullMapKeyName()
                    + " 키로 전체 지도를 열고 '웹지도 인증' 버튼을 눌러 인증해주세요.");
        } else {
            announce("웹지도가 Cloudflare 인증으로 막혀 있어요. MCEF 모드가 있어야 "
                    + "게임 안에서 인증하고 지도를 볼 수 있습니다.");
        }
    }

    private static void announce(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[PlanetMap] ")
                        .formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal(message).formatted(Formatting.YELLOW)), false);
            }
        });
    }
}
