package org.ajeet.store;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Agent side of the link with the web dashboard, through the shared database
 * (the agent opens no port):
 * <ul>
 *   <li>{@code sync_agent} — single heartbeat row refreshed every few seconds
 *       (host, last sync result, last error); the dashboard shows it.</li>
 *   <li>{@code sync_requests} — "Sync device" requests queued by the
 *       dashboard, claimed here with {@code FOR UPDATE SKIP LOCKED}.</li>
 * </ul>
 * Keep the DDL in sync with the web-dashboard's SyncRequests.
 */
public class AgentQueue {

    /** Pending requests older than this are expired, not executed. */
    private static final int REQUEST_TTL_SECONDS = 120;

    private final Connection conn;

    public AgentQueue(String url, String user, String password) throws IOException {
        try {
            conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sync_agent (
                            id            INT PRIMARY KEY CHECK (id = 1),
                            host          TEXT NOT NULL,
                            device        TEXT NOT NULL,
                            last_seen     TIMESTAMP NOT NULL,
                            last_sync_at  TIMESTAMP,
                            last_appended INT,
                            last_error    TEXT
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sync_requests (
                            id           BIGSERIAL PRIMARY KEY,
                            requested_at TIMESTAMP NOT NULL DEFAULT now(),
                            requested_by TEXT NOT NULL,
                            since        TIMESTAMP,
                            status       TEXT NOT NULL DEFAULT 'pending',
                            finished_at  TIMESTAMP,
                            appended     INT,
                            error        TEXT
                        )""");
            }
        } catch (SQLException e) {
            throw new IOException("sync queue init failed: " + e.getMessage(), e);
        }
    }

    /** Refreshes the heartbeat; pass a result to also record a sync attempt. */
    public synchronized void heartbeat(String host, String device,
                                       SyncOutcome outcome) throws IOException {
        String sql = outcome == null
                ? """
                  INSERT INTO sync_agent (id, host, device, last_seen) VALUES (1, ?, ?, now())
                  ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host,
                      device = EXCLUDED.device, last_seen = now()"""
                : """
                  INSERT INTO sync_agent (id, host, device, last_seen, last_sync_at, last_appended, last_error)
                  VALUES (1, ?, ?, now(), now(), ?, ?)
                  ON CONFLICT (id) DO UPDATE SET host = EXCLUDED.host, device = EXCLUDED.device,
                      last_seen = now(), last_sync_at = now(),
                      last_appended = EXCLUDED.last_appended, last_error = EXCLUDED.last_error""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setString(2, device);
            if (outcome != null) {
                ps.setInt(3, outcome.appended());
                ps.setString(4, outcome.error());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("heartbeat failed: " + e.getMessage(), e);
        }
    }

    /** Claims the oldest fresh pending request, or returns null. */
    public synchronized Request claimNext() throws IOException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sync_requests SET status = 'expired', finished_at = now() "
                    + "WHERE status = 'pending' AND requested_at < now() - interval '"
                    + REQUEST_TTL_SECONDS + " seconds'");
            try (ResultSet rs = st.executeQuery("""
                    UPDATE sync_requests SET status = 'running'
                    WHERE id = (SELECT id FROM sync_requests WHERE status = 'pending'
                                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                    RETURNING id, since""")) {
                if (!rs.next()) return null;
                Timestamp since = rs.getTimestamp(2);
                return new Request(rs.getLong(1), since == null ? null : since.toLocalDateTime());
            }
        } catch (SQLException e) {
            throw new IOException("claim failed: " + e.getMessage(), e);
        }
    }

    public synchronized void finish(long id, SyncOutcome outcome) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE sync_requests SET status = ?, finished_at = now(), appended = ?, error = ?
                WHERE id = ?""")) {
            ps.setString(1, outcome.error() == null ? "done" : "failed");
            ps.setInt(2, outcome.appended());
            ps.setString(3, outcome.error());
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("finish failed: " + e.getMessage(), e);
        }
    }

    public record Request(long id, LocalDateTime since) {}

    /** Result of one sync attempt; {@code error} is null on success. */
    public record SyncOutcome(int appended, String error) {}
}
