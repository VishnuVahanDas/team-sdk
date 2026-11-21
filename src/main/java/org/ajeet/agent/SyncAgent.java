package org.ajeet.agent;

import org.ajeet.device.DeviceClient;
import org.ajeet.store.AgentQueue;
import org.ajeet.store.AgentQueue.SyncOutcome;
import org.ajeet.store.PunchStore;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

/**
 * Headless device-sync loop for the Windows PC next to the terminal.
 *
 * Every {@link #TICK} it refreshes its heartbeat and runs any "sync now"
 * request queued by the web dashboard; independently it auto-syncs every
 * {@code interval}. Errors are recorded in the heartbeat and never stop the
 * loop, so a device that is briefly offline recovers on the next tick.
 */
public class SyncAgent {

    private static final Duration TICK = Duration.ofSeconds(3);

    private final DeviceClient device;
    private final PunchStore store;
    private final AgentQueue queue;
    private final Duration interval;
    private final String host;

    public SyncAgent(DeviceClient device, PunchStore store, AgentQueue queue, Duration interval) {
        this.device = device;
        this.store = store;
        this.queue = queue;
        this.interval = interval;
        this.host = hostName();
    }

    /** Runs until the process is stopped. */
    public void run() throws InterruptedException {
        System.out.printf("Sync agent '%s' started: %s, auto-sync every %ds%n",
                host, device.describe(), interval.toSeconds());
        Instant nextAuto = Instant.now(); // sync once immediately on start
        while (true) {
            try {
                AgentQueue.Request req = queue.claimNext();
                if (req != null) {
                    SyncOutcome out = syncOnce(req.since(), "request #" + req.id());
                    queue.finish(req.id(), out);
                    nextAuto = Instant.now().plus(interval);
                } else if (!Instant.now().isBefore(nextAuto)) {
                    syncOnce(null, "auto");
                    nextAuto = Instant.now().plus(interval);
                } else {
                    queue.heartbeat(host, device.describe(), null);
                }
            } catch (Exception e) {
                // DB hiccup: log and keep going; the next tick retries.
                System.err.println("agent tick failed: " + e.getMessage());
            }
            Thread.sleep(TICK);
        }
    }

    private SyncOutcome syncOnce(java.time.LocalDateTime since, String reason) throws Exception {
        SyncOutcome out;
        try {
            DeviceClient.SyncResult r = device.sync(store, since);
            out = new SyncOutcome(r.appended(), null);
            System.out.printf("[%s] synced %d new punch(es)%n", reason, r.appended());
        } catch (Exception | LinkageError e) {
            out = new SyncOutcome(0, e.getMessage());
            System.err.printf("[%s] sync failed: %s%n", reason, e.getMessage());
        }
        queue.heartbeat(host, device.describe(), out);
        return out;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "sync-agent";
        }
    }
}
