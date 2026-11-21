package org.ajeet.agent;

import org.ajeet.config.Env;
import org.ajeet.device.DeviceClient;
import org.ajeet.device.DeviceConfig;
import org.ajeet.store.AgentQueue;
import org.ajeet.store.PunchStore;

import java.time.Duration;

/**
 * Windows sync agent: headless device sync for a PC on the terminal's LAN.
 * Writes punches into the shared PostgreSQL and serves "sync now" requests
 * queued by the web dashboard; it opens no port.
 */
public class SyncAgentMain {

    public static void main(String[] args) throws Exception {
        String pgUrl = Env.get("PG_URL", null);
        if (pgUrl == null || pgUrl.isBlank())
            throw new IllegalStateException("Sync agent needs PG_URL (the web dashboard's database)");
        if (!Env.isWindows())
            System.err.println("WARNING: agent is not on Windows; device sync will fail (SDK is a Windows DLL)");
        DeviceClient device = DeviceConfig.fromEnv();
        if (device == null)
            throw new IllegalStateException("Sync agent needs DEVICE_PASSWORD (device comm key)");
        String user = Env.get("PG_USER", "postgres"), pass = Env.get("PG_PASSWORD", "");
        PunchStore store = new PunchStore(pgUrl, user, pass);
        AgentQueue queue = new AgentQueue(pgUrl, user, pass);
        int seconds = Integer.parseInt(Env.get("SYNC_INTERVAL_SECONDS", "120"));
        new SyncAgent(device, store, queue, Duration.ofSeconds(Math.max(15, seconds))).run();
    }
}
