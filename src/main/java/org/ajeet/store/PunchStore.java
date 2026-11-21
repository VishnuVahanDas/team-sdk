package org.ajeet.store;

import org.ajeet.device.Punch;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Write side of the shared attendance database: device users and punches.
 *
 * The web dashboard reads these same tables. Both projects create them with
 * IF NOT EXISTS, so either can start first; keep the DDL in sync with the
 * web-dashboard's AttendanceStore. UNIQUE (enroll_number, punch_time,
 * verify_label) makes inserts idempotent, so a re-sync never duplicates.
 */
public class PunchStore {

    private final Connection conn;
    private final String url;

    public PunchStore(String url, String user, String password) throws IOException {
        this.url = url;
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            enroll_number INT PRIMARY KEY,
                            name          TEXT NOT NULL DEFAULT ''
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS punches (
                            id            BIGSERIAL PRIMARY KEY,
                            enroll_number INT NOT NULL,
                            punch_time    TIMESTAMP NOT NULL,
                            verify_label  TEXT NOT NULL,
                            UNIQUE (enroll_number, punch_time, verify_label)
                        )""");
            }
            relabelLegacyPunches();
        } catch (SQLException e) {
            throw new IOException("PostgreSQL init failed: " + e.getMessage(), e);
        }
    }

    /**
     * One-time migration: labels written before the packed verify mode was
     * decoded ("Mode 407", or wrong names like "Face" for code 7) are
     * rewritten with {@link Punch#label}. Needed because verify_label is part
     * of the dedup key - otherwise a re-sync would duplicate every punch.
     * Runs once (marker in schema_migrations), in one transaction.
     */
    private void relabelLegacyPunches() throws SQLException {
        final String id = "2026-11-21-verify-labels";
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations (id TEXT PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT now())");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_migrations WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        List<String> labels = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT verify_label FROM punches")) {
            while (rs.next()) labels.add(rs.getString(1));
        }
        conn.setAutoCommit(false);
        try (PreparedStatement upd = conn.prepareStatement("""
                     UPDATE punches p SET verify_label = ?
                     WHERE p.verify_label = ? AND NOT EXISTS (
                         SELECT 1 FROM punches q WHERE q.enroll_number = p.enroll_number
                           AND q.punch_time = p.punch_time AND q.verify_label = ?)""");
             PreparedStatement del = conn.prepareStatement("DELETE FROM punches WHERE verify_label = ?");
             PreparedStatement mark = conn.prepareStatement("INSERT INTO schema_migrations (id) VALUES (?)")) {
            int changed = 0;
            for (String old : labels) {
                Integer code = Punch.legacyCode(old);
                if (code == null) continue;
                String now = Punch.label(code);
                if (now.equals(old)) continue;
                upd.setString(1, now); upd.setString(2, old); upd.setString(3, now);
                changed += upd.executeUpdate();
                del.setString(1, old); // leftovers already exist under the new label
                del.executeUpdate();
            }
            mark.setString(1, id);
            mark.executeUpdate();
            conn.commit();
            System.out.printf("relabelled %d legacy punch(es) with decoded verify modes%n", changed);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Inserts punches; ones already stored are skipped. */
    public synchronized void insertPunches(List<Punch> punches) throws IOException {
        if (punches.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO punches (enroll_number, punch_time, verify_label)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING""")) {
            for (Punch p : punches) {
                ps.setInt(1, p.enrollNumber());
                ps.setObject(2, p.time());
                ps.setString(3, p.verifyLabel());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IOException("punch insert failed: " + e.getMessage(), e);
        }
    }

    /** Upserts device users so the dashboard shows names, not bare enroll numbers. */
    public synchronized void upsertUsers(Map<Integer, String> users) throws IOException {
        if (users.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO users (enroll_number, name) VALUES (?, ?)
                ON CONFLICT (enroll_number)
                DO UPDATE SET name = EXCLUDED.name
                WHERE users.name <> EXCLUDED.name""")) {
            for (Map.Entry<Integer, String> e : users.entrySet()) {
                ps.setInt(1, e.getKey());
                ps.setString(2, e.getValue() == null ? "" : e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IOException("user upsert failed: " + e.getMessage(), e);
        }
    }

    public String describe() {
        return "PostgreSQL (" + url + ")";
    }
}
