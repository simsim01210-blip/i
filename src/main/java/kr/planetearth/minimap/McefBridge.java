package kr.planetearth.minimap;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The only class that touches MCEF. Keeps one small, invisible browser page open on
 *  the web map's own site and runs ordinary same-site fetch() calls in it — the same
 *  requests the website itself makes, sent by a real Chromium, so the site treats
 *  them like any visitor's browser. Results come back through a JCEF message router.
 *  Only ever loaded when the MCEF mod is installed (see {@link WebMapBrowser}). */
final class McefBridge {
    private static final String QUERY_FUNCTION = "planetmapQuery";
    private static final String CANCEL_FUNCTION = "planetmapQueryCancel";
    // Kept small on purpose: the page holds requests in a queue and runs at most this
    // many at once, about what a person browsing the map would cause.
    private static final int MAX_IN_FLIGHT = 6;
    private static final int MAX_DISPATCH_PER_FRAME = 24;
    private static final long BRIDGE_RETRY_MILLIS = 5_000L;
    private static final String BRIDGE_SCRIPT = """
            (function () {
              if (window.__planetmap) return;
              var queue = [], active = 0, LIMIT = %d;
              function send(o) {
                window.%s({request: JSON.stringify(o), onSuccess: function () {}, onFailure: function () {}});
              }
              function next() {
                while (active < LIMIT && queue.length) {
                  var job = queue.shift();
                  active++;
                  fetch(job.url, {credentials: 'include', cache: 'no-store', headers: job.headers})
                    .then(function (r) {
                      return r.blob().then(function (blob) {
                        return new Promise(function (resolve) {
                          var reader = new FileReader();
                          reader.onload = function () {
                            var s = String(reader.result), i = s.indexOf(',');
                            resolve(i < 0 ? '' : s.substring(i + 1));
                          };
                          reader.onerror = function () { resolve(''); };
                          reader.readAsDataURL(blob);
                        });
                      }).then(function (b64) {
                        send({id: job.id, s: r.status, b: b64, e: r.headers.get('etag'),
                              m: r.headers.get('last-modified'), c: r.headers.get('cf-mitigated')});
                      });
                    })
                    .catch(function (err) { send({id: job.id, s: 0, b: '', x: String(err)}); })
                    .then(function () { active--; next(); });
                }
              }
              window.__planetmap = {
                run: function (id, url, headers) {
                  queue.push({id: id, url: url, headers: JSON.parse(headers)});
                  next();
                }
              };
            })();
            """.formatted(MAX_IN_FLIGHT, QUERY_FUNCTION);

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Queue<Integer> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "planetearth-browser-decode");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean handlersInstalled;
    private static volatile MCEFBrowser bridge;
    private static String bridgeUrl;
    private static volatile boolean bridgeReady;
    private static long bridgeRetryAt;
    private static volatile CefBrowser verifyBrowser;
    private static volatile Runnable verifyPassed;
    // When the player last got past the check. Requests the page had already sent
    // before that still come back with the check page afterwards — those must not
    // flag "인증 필요" again, or passing it just made the prompt come right back.
    private static volatile long verifiedAtMillis;
    private static volatile boolean loggedChallengeSinceVerify;

    private McefBridge() {}

    private static final class Pending {
        private final String url;
        private final String headersJson;
        private final CompletableFuture<WebMapFetcher.Response> future;
        private volatile long dispatchedAtMillis;

        private Pending(String url, String headersJson, CompletableFuture<WebMapFetcher.Response> future) {
            this.url = url;
            this.headersJson = headersJson;
            this.future = future;
        }
    }

    static boolean isAvailable() {
        try {
            return MCEF.isInitialized();
        } catch (Throwable error) {
            // A different MCEF version with an incompatible API: behave as if absent.
            return false;
        }
    }

    static CompletableFuture<WebMapFetcher.Response> fetch(String url, Map<String, String> headers,
                                                           Duration timeout) {
        int id = NEXT_ID.incrementAndGet();
        CompletableFuture<WebMapFetcher.Response> future = new CompletableFuture<>();
        PENDING.put(id, new Pending(url, headersJson(headers), future));
        QUEUE.add(id);
        // Page-side queueing can hold a request a while before it even starts, so the
        // deadline is a little more generous than a direct request's.
        future.orTimeout(timeout.toMillis() + 10_000L, TimeUnit.MILLISECONDS)
                .whenComplete((unused, error) -> PENDING.remove(id));
        return future;
    }

    /** Render thread. CEF calls are made from here only. */
    static void pump() {
        if (!isAvailable()) return;
        try {
            ensureBridge();
            if (!bridgeReady) {
                if (bridge != null && System.currentTimeMillis() >= bridgeRetryAt) {
                    bridgeRetryAt = System.currentTimeMillis() + BRIDGE_RETRY_MILLIS;
                    bridge.loadURL(bridgeUrl);
                }
                return;
            }
            Integer id;
            int dispatched = 0;
            while (dispatched < MAX_DISPATCH_PER_FRAME && (id = QUEUE.poll()) != null) {
                Pending pending = PENDING.get(id);
                if (pending == null) continue;
                pending.dispatchedAtMillis = System.currentTimeMillis();
                bridge.executeJavaScript("window.__planetmap&&window.__planetmap.run(" + id + ","
                        + jsString(pending.url) + "," + jsString(pending.headersJson) + ")", bridgeUrl, 0);
                dispatched++;
            }
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("게임 내 브라우저 처리 중 오류가 발생했습니다", error);
        }
    }

    static void openVerifyScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(new WebMapVerifyScreen(client.currentScreen)));
    }

    /** Opens a visible browser for {@link WebMapVerifyScreen}; {@code onPassed} runs
     *  once a page in it loads normally, i.e. after the player got past the check. */
    static MCEFBrowser createVerifyBrowser(String url, Runnable onPassed) {
        installHandlers();
        verifyPassed = onPassed;
        MCEFBrowser browser = MCEF.createBrowser(url, false);
        verifyBrowser = browser;
        return browser;
    }

    static void closeVerifyBrowser(MCEFBrowser browser) {
        if (verifyBrowser == browser) {
            verifyBrowser = null;
            verifyPassed = null;
        }
        try {
            browser.close();
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.debug("브라우저 닫기 실패", error);
        }
    }

    private static void ensureBridge() {
        if (bridge != null) return;
        installHandlers();
        // A tiny static file on the map's own site: gives the page the right origin for
        // same-site fetches without loading (and running) the whole web map app.
        bridgeUrl = PlanetEarthMinimapClient.config.mapBaseUrl() + "/standalone/config.js";
        bridgeRetryAt = System.currentTimeMillis() + BRIDGE_RETRY_MILLIS;
        bridge = MCEF.createBrowser(bridgeUrl, false);
        bridge.resize(16, 16);
    }

    private static void installHandlers() {
        if (handlersInstalled) return;
        handlersInstalled = true;
        CefMessageRouter router = CefMessageRouter.create(
                new CefMessageRouter.CefMessageRouterConfig(QUERY_FUNCTION, CANCEL_FUNCTION));
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                                   boolean persistent, CefQueryCallback callback) {
                if (browser != bridge) return false;
                callback.success("");
                // Base64-decoding tiles is real work; keep it off the render thread
                // (CEF delivers this on the thread that pumps its message loop).
                DECODER.execute(() -> complete(request));
                return true;
            }
        }, true);
        MCEF.getClient().getHandle().addMessageRouter(router);
        MCEF.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame == null || !frame.isMain()) return;
                if (browser == bridge) {
                    if (httpStatusCode == 200) {
                        browser.executeJavaScript(BRIDGE_SCRIPT, bridgeUrl, 0);
                        bridgeReady = true;
                        PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 연결 페이지 준비됨");
                    } else {
                        bridgeReady = false;
                        PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 연결 페이지 응답 {}", httpStatusCode);
                        // The page itself was put behind the check: no fetch will ever
                        // report it, so ask the player from here. pump() reloads it
                        // every few seconds, which picks up the cookie once they pass.
                        if (httpStatusCode == 403 || httpStatusCode == 503) {
                            WebMapBrowser.onBrowserChallenge();
                        }
                    }
                } else if (browser == verifyBrowser) {
                    String url = frame.getURL();
                    PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 인증 창 페이지 응답 {} ({})",
                            httpStatusCode, url);
                    logCookieNames(url);
                    // Only the data page itself answering normally counts — not some
                    // intermediate page of the check flow that happens to return 200.
                    if (httpStatusCode != 200 || url == null || url.contains("/cdn-cgi/")
                            || !url.contains("/up/world/")) return;
                    verifiedAtMillis = System.currentTimeMillis();
                    loggedChallengeSinceVerify = false;
                    Runnable passed = verifyPassed;
                    if (passed != null) passed.run();
                }
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (browser == bridge && frame != null && frame.isMain()) bridgeReady = false;
            }
        });
    }

    private static void complete(String request) {
        try {
            JsonObject result = JsonParser.parseString(request).getAsJsonObject();
            int id = result.get("id").getAsInt();
            Pending pending = PENDING.remove(id);
            if (pending == null) return;
            int status = result.get("s").getAsInt();
            String encoded = stringOrNull(result, "b");
            byte[] body = encoded == null || encoded.isEmpty()
                    ? new byte[0] : Base64.getDecoder().decode(encoded);
            boolean challenge = WebMapFetcher.isChallenge(status, stringOrNull(result, "c"));
            if (challenge) {
                // Sent before the player passed the check: a leftover, not a new ask.
                if (pending.dispatchedAtMillis >= verifiedAtMillis) {
                    if (!loggedChallengeSinceVerify) {
                        loggedChallengeSinceVerify = true;
                        PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 요청이 확인 페이지로 막힘 "
                                + "(마지막 인증 후 {}ms): {}",
                                verifiedAtMillis == 0L ? -1 : System.currentTimeMillis() - verifiedAtMillis,
                                pending.url);
                    }
                    WebMapBrowser.onBrowserChallenge();
                }
            } else if (status > 0) {
                if (WebMapBrowser.verificationNeeded()) {
                    PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 확인 없이 응답 {}: {}",
                            status, pending.url);
                }
                WebMapBrowser.onBrowserSuccess();
            }
            if (status == 0) {
                pending.future.completeExceptionally(new java.io.IOException(
                        "browser fetch failed: " + stringOrNull(result, "x")));
                return;
            }
            pending.future.complete(new WebMapFetcher.Response(status, body,
                    stringOrNull(result, "e"), stringOrNull(result, "m"), challenge));
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.debug("브라우저 응답을 해석하지 못했습니다", error);
        }
    }

    /** Diagnostics only: which cookies (by name — values are never read or logged)
     *  the browser holds for the map site. Tells apart "the check's cookie never got
     *  stored" from "it's stored, but the site keeps asking anyway". */
    private static void logCookieNames(String url) {
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            // The visitor is never called when there are no cookies at all, so a
            // missing "저장된 쿠키 이름" line right after this one means none.
            PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 쿠키 조회");
            org.cef.network.CefCookieManager.getGlobalManager().visitUrlCookies(url, true,
                    (cookie, count, total, delete) -> {
                        names.add(cookie.name);
                        if (count + 1 >= total) {
                            PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 저장된 쿠키 이름: {}", names);
                        }
                        return true;
                    });
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.info("[웹지도 브라우저] 쿠키 확인 실패: {}", error.toString());
        }
    }

    private static String stringOrNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static String headersJson(Map<String, String> headers) {
        JsonObject object = new JsonObject();
        if (headers != null) headers.forEach(object::addProperty);
        return object.toString();
    }

    /** A JS string literal; JSON string escaping is valid JavaScript. */
    private static String jsString(String value) {
        return new com.google.gson.JsonPrimitive(value).toString();
    }
}
