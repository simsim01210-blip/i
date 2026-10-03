package kr.planetearth.minimap;

/** Tracks whether the PlanetEarth web map (LiveAtlas) is actually answering. Fed by
 *  both the player feed and tile downloads; while it's judged down, players are
 *  hidden (their last positions would be stale), already-cached terrain stays on
 *  screen instead of the loading indicator, and tile requests drop to occasional
 *  probes instead of hammering a server that isn't responding. */
final class WebMapHealth {
    // A single burst of failures (one slow moment, one bad tile) shouldn't flip the
    // whole map into "connection problem" mode — it takes several failures in a row
    // with nothing succeeding in between for a while.
    private static final int FAILURES_TO_DOWN = 4;
    private static final long NO_SUCCESS_TO_DOWN_MILLIS = 5_000L;
    // A request that never answers at all (server hanging, not refusing) produces no
    // failure until its timeout, so a feed request outstanding this long counts too.
    private static final long HANGING_REQUEST_MILLIS = 6_000L;
    private static final long PROBE_INTERVAL_MILLIS = 2_000L;

    private static volatile int consecutiveFailures;
    private static volatile long lastSuccessMillis = System.currentTimeMillis();
    private static volatile long feedRequestStartedMillis;
    private static volatile boolean down;
    private static volatile long nextProbeMillis;

    private WebMapHealth() {}

    static void recordSuccess() {
        consecutiveFailures = 0;
        lastSuccessMillis = System.currentTimeMillis();
        if (down) {
            down = false;
            // Anything that failed during the outage is backed off; retry it all now
            // so the map catches up immediately instead of trickling back over minutes.
            LiveAtlasTileManager.onWebMapRecovered();
        }
    }

    static void recordFailure() {
        consecutiveFailures++;
        evaluate();
    }

    static void feedRequestStarted() {
        feedRequestStartedMillis = System.currentTimeMillis();
    }

    static void feedRequestFinished() {
        feedRequestStartedMillis = 0L;
    }

    static boolean isDown() {
        evaluate();
        return down;
    }

    /** While down, lets roughly one tile request through every couple of seconds so
     *  recovery is still noticed even if the player feed isn't being polled. */
    static boolean allowProbe() {
        long now = System.currentTimeMillis();
        if (now < nextProbeMillis) return false;
        nextProbeMillis = now + PROBE_INTERVAL_MILLIS;
        return true;
    }

    private static void evaluate() {
        if (down) return;
        long now = System.currentTimeMillis();
        boolean stale = now - lastSuccessMillis > NO_SUCCESS_TO_DOWN_MILLIS;
        long started = feedRequestStartedMillis;
        boolean hanging = started != 0L && now - started > HANGING_REQUEST_MILLIS;
        if (stale && (consecutiveFailures >= FAILURES_TO_DOWN || hanging)) {
            down = true;
        }
    }
}
