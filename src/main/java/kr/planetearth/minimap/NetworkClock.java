package kr.planetearth.minimap;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** The status-bar clock used to read the PC's own clock, which is commonly seconds
 *  off — Windows only re-syncs it rarely, and not at all when the time service is
 *  stopped. This asks an internet time server (plain SNTP, one small UDP packet) for
 *  the real time in the background and keeps the difference, so the clock is right
 *  regardless of the PC. If no server answers, it simply stays on the PC clock. */
final class NetworkClock {
    private static final String[] SERVERS = {
            "time.google.com", "time.windows.com", "kr.pool.ntp.org"
    };
    private static final long RESYNC_MILLIS = 30L * 60L * 1000L;
    private static final long RETRY_MILLIS = 60L * 1000L;
    // Seconds between the NTP epoch (1900) and the Unix epoch (1970).
    private static final long NTP_TO_UNIX_SECONDS = 2_208_988_800L;

    private static volatile long offsetMillis;
    private static volatile long nextSyncMillis;
    private static volatile boolean syncing;

    private NetworkClock() {}

    /** Current time in Unix milliseconds, corrected by the last successful sync. */
    static long currentTimeMillis() {
        long now = System.currentTimeMillis();
        if (!syncing && now >= nextSyncMillis) startSync(now);
        return now + offsetMillis;
    }

    private static void startSync(long now) {
        syncing = true;
        nextSyncMillis = now + RETRY_MILLIS;
        Thread thread = new Thread(() -> {
            try {
                for (String server : SERVERS) {
                    Long offset = query(server);
                    if (offset != null) {
                        offsetMillis = offset;
                        nextSyncMillis = System.currentTimeMillis() + RESYNC_MILLIS;
                        return;
                    }
                }
            } finally {
                syncing = false;
            }
        }, "planetearth-clock-sync");
        thread.setDaemon(true);
        thread.start();
    }

    /** One SNTP round trip; returns the server-minus-local offset, or null on failure. */
    private static Long query(String server) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            byte[] buffer = new byte[48];
            buffer[0] = 0x1B; // LI 0, version 3, mode 3 (client)
            InetAddress address = InetAddress.getByName(server);
            long sent = System.currentTimeMillis();
            socket.send(new DatagramPacket(buffer, buffer.length, address, 123));
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            long received = System.currentTimeMillis();
            if (response.getLength() < 48) return null;
            long serverReceive = ntpMillis(buffer, 32);
            long serverTransmit = ntpMillis(buffer, 40);
            if (serverTransmit <= 0) return null;
            // Standard NTP offset: averages out the network delay both ways.
            return ((serverReceive - sent) + (serverTransmit - received)) / 2;
        } catch (Exception error) {
            PlanetEarthMinimapClient.LOGGER.debug("시간 서버 {} 응답 없음", server, error);
            return null;
        }
    }

    private static long ntpMillis(byte[] buffer, int offset) {
        long seconds = 0;
        long fraction = 0;
        for (int i = 0; i < 4; i++) seconds = (seconds << 8) | (buffer[offset + i] & 0xFF);
        for (int i = 4; i < 8; i++) fraction = (fraction << 8) | (buffer[offset + i] & 0xFF);
        if (seconds == 0) return 0;
        // NTP era 1 starts in 2036, when the 32-bit seconds field wraps back to zero.
        if ((seconds & 0x8000_0000L) == 0) seconds += 1L << 32;
        return (seconds - NTP_TO_UNIX_SECONDS) * 1000L + (fraction * 1000L >>> 32);
    }
}
