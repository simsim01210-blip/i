package kr.planetearth.minimap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Every web map download goes through here. Normally that's a plain HTTP request,
 *  exactly as before. When the web map sits behind a Cloudflare "Just a moment..."
 *  check that turns away anything that isn't a real browser, and the player has the
 *  MCEF (in-game Chromium) mod installed, requests are instead made by a real
 *  browser inside the game — see {@link WebMapBrowser}. If that browser gets a check
 *  too, the player solves it themselves in a browser window the mod opens; nothing
 *  here ever tries to answer such a check on its own. */
final class WebMapFetcher {
    private WebMapFetcher() {}

    record Response(int status, byte[] body, String etag, String lastModified, boolean challenge) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static CompletableFuture<Response> fetch(HttpClient client, String url, Duration timeout,
                                             Map<String, String> headers) {
        if (WebMapBrowser.isRouting()) {
            return WebMapBrowser.fetch(url, headers, timeout);
        }
        String base = PlanetEarthMinimapClient.config.mapBaseUrl();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "PlanetEarthMinimap/0.1")
                .header("Referer", base + "/")
                .GET();
        if (headers != null) headers.forEach(builder::header);
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenCompose(response -> {
                    boolean challenge = isChallenge(response.statusCode(),
                            response.headers().firstValue("cf-mitigated").orElse(null));
                    if (challenge) {
                        WebMapBrowser.onChallengeDetected();
                        // Retry this very request through the browser when it's
                        // available, so the switch-over costs no extra round of failures.
                        if (WebMapBrowser.isRouting()) {
                            return WebMapBrowser.fetch(url, headers, timeout);
                        }
                    }
                    return CompletableFuture.completedFuture(new Response(
                            response.statusCode(), response.body(),
                            response.headers().firstValue("ETag").orElse(null),
                            response.headers().firstValue("Last-Modified").orElse(null),
                            challenge));
                });
    }

    static boolean isChallenge(int status, String cfMitigated) {
        return (status == 403 || status == 503) && "challenge".equalsIgnoreCase(cfMitigated);
    }
}
