package org.ajeet.store;

import org.ajeet.device.Punch;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
        } catch (SQLException e) {
            throw new IOException("PostgreSQL init failed: " + e.getMessage(), e);
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
