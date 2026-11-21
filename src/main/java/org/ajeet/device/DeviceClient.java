package org.ajeet.device;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.ajeet.store.PunchStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls attendance punches from an SBXPC-family terminal over TCP.
 *
 * Dedup strategy: the device log is a ring buffer replayed in full on every
 * pull, so a high-water mark (the newest synced punch timestamp) is persisted
 * in {@code device-sync-state.txt} and only newer punches are appended. This
 * avoids EmptyGeneralLogData, which would lose punches if a DB write fails.
 */
public class DeviceClient {

    /** Sync watermark for a first run: everything still in the device log. */
    private static final LocalDateTime DEFAULT_SINCE = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final String ip;
    private final int port;
    private final int password;
    private final int machine;
    private final Path stateFile;
    private Sbxpc dll;

    public DeviceClient(String ip, int port, int password, int machine,
                        Path dllDir, Path stateFile) {
        this.ip = ip;
        this.port = port;
        this.password = password;
        this.machine = machine;
        this.stateFile = stateFile;
        // Must run before the first Native.load so JNA can resolve the DLL
        // and its sibling dependency (SBPCCOMM64.dll) from the same folder.
        System.setProperty("jna.library.path", dllDir.toAbsolutePath().toString());
    }

    private Sbxpc dll() throws IOException {
        if (dll == null) {
            // This SDK exports its symbols with a leading underscore
            // (_ConnectTcpip, _GetGeneralLogData, ...).
            Map<String, Object> options = Map.of(Library.OPTION_FUNCTION_MAPPER,
                    (FunctionMapper) (lib, method) -> "_" + method.getName());
            try {
                dll = Native.load("SBXPCDLL64", Sbxpc.class, options);
            } catch (UnsatisfiedLinkError e) {
                // An Error, not an Exception: rethrow so the API answers instead
                // of dropping the connection. The SDK is Windows x64 only.
                throw new IOException("device SDK unavailable: SBXPCDLL64.dll can only be loaded"
                        + " on 64-bit Windows (os=" + System.getProperty("os.name")
                        + ", jna.library.path=" + System.getProperty("jna.library.path") + ")", e);
            }
        }
        return dll;
    }

    /** Opens a TCP session; caller must call Disconnect in a finally block. */
    private void connect(Sbxpc sdk) throws IOException {
        // _ConnectTcpip takes a void** -> BSTR-style UTF-16LE string buffer.
        Memory ipChars = new Memory((ip.length() + 1) * 2L);
        ipChars.setWideString(0, ip);
        PointerByReference ipArg = new PointerByReference(ipChars);
        if (sdk.ConnectTcpip(machine, ipArg, port, password) == 0) {
            IntByReference err = new IntByReference();
            sdk.GetLastError(machine, err);
            throw new IOException("ConnectTcpip to " + ip + ":" + port
                    + " failed, sdk error " + err.getValue()
                    + " (check comm key, machine id, and reachability)");
        }
    }

    /**
     * Opens the session, replays the log buffer, closes the session.
     *
     * @param all true reads every record on the device (ReadAllGLogData);
     *            false reads only records not yet marked read, which misses
     *            history another tool (or the device itself) already marked.
     */
    public List<Punch> pullLogs(boolean all) throws IOException {
        Sbxpc sdk = dll();
        connect(sdk);
        try {
            List<Punch> punches = new ArrayList<>();
            byte loaded = all ? sdk.ReadAllGLogData(machine)
                              : sdk.ReadGeneralLogData(machine, (byte) 0);
            if (loaded == 0) {
                IntByReference err = new IntByReference();
                sdk.GetLastError(machine, err);
                System.err.printf("%s returned 0, sdk error %d (empty log or read failure)%n",
                        all ? "ReadAllGLogData" : "ReadGeneralLogData", err.getValue());
                return punches;
            }
            IntByReference m = new IntByReference(), enroll = new IntByReference(),
                    em = new IntByReference(), verify = new IntByReference(),
                    y = new IntByReference(), mo = new IntByReference(),
                    d = new IntByReference(), h = new IntByReference(),
                    mi = new IntByReference(), s = new IntByReference();
            while ((all ? sdk.GetAllGLogData(machine, m, enroll, em, verify, y, mo, d, h, mi, s)
                        : sdk.GetGeneralLogData(machine, m, enroll, em, verify, y, mo, d, h, mi, s)) != 0) {
                punches.add(new Punch(enroll.getValue(), verify.getValue(),
                        LocalDateTime.of(y.getValue(), mo.getValue(), d.getValue(),
                                h.getValue(), mi.getValue(), s.getValue())));
            }
            return punches;
        } finally {
            sdk.Disconnect(machine);
        }
    }

    /**
     * Pulls the device's enrolled-user list as enrollNumber -> name.
     * One user may appear multiple times (once per finger/backup); the
     * first entry wins and names are fetched via GetUserName1.
     */
    public Map<Integer, String> pullUsers() throws IOException {
        Sbxpc sdk = dll();
        connect(sdk);
        try {
            Map<Integer, String> users = new HashMap<>();
            if (sdk.ReadAllUserID(machine) == 0) {
                return users;
            }
            IntByReference enroll = new IntByReference(), em = new IntByReference(),
                    backup = new IntByReference(), priv = new IntByReference(),
                    enable = new IntByReference();
            while (sdk.GetAllUserID(machine, enroll, em, backup, priv, enable) != 0) {
                int id = enroll.getValue();
                if (users.containsKey(id)) continue; // one row per finger index
                PointerByReference namePtr = new PointerByReference();
                String name = "";
                if (sdk.GetUserName1(machine, id, namePtr) != 0
                        && namePtr.getValue() != null) {
                    name = namePtr.getValue().getWideString(0);
                }
                users.put(id, name == null ? "" : name.trim());
            }
            return users;
        } finally {
            sdk.Disconnect(machine);
        }
    }

    /**
     * Pulls the device log and stores punches newer than the watermark. Pass
     * {@code since} to reset the watermark (e.g. backfill), or {@code null} to
     * use the stored one (the whole device log on first run).
     */
    public SyncResult sync(PunchStore store, LocalDateTime since) throws IOException {
        boolean firstRun = since == null && !Files.exists(stateFile);
        LocalDateTime watermark = since != null ? since : readWatermark();
        // Backfills and first runs need records already marked read on the device.
        List<Punch> all = pullLogs(since != null || firstRun);
        System.out.printf("device returned %d record(s), keeping those after %s%n", all.size(), watermark);
        List<Punch> fresh = all.stream()
                .filter(p -> p.time().isAfter(watermark))
                .sorted(Comparator.comparing(Punch::time))
                .toList();
        store.insertPunches(fresh);
        // Refresh names so the dashboard shows them instead of bare enroll numbers.
        store.upsertUsers(pullUsers());
        if (!fresh.isEmpty()) {
            writeWatermark(fresh.getLast().time());
        }
        return new SyncResult(fresh.size(), watermark,
                fresh.isEmpty() ? watermark : fresh.getLast().time());
    }

    public LocalDateTime readWatermark() {
        try {
            if (Files.exists(stateFile)) {
                String text = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return LocalDateTime.parse(text);
                }
            }
        } catch (Exception ignored) {
            // corrupt or unreadable state -> fall through to default
        }
        return DEFAULT_SINCE;
    }

    private void writeWatermark(LocalDateTime time) throws IOException {
        Files.writeString(stateFile,
                time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                StandardCharsets.UTF_8);
    }

    public String describe() {
        return "Z200BW@" + ip + ":" + port + " (machine " + machine + ")";
    }

    public record SyncResult(int appended, LocalDateTime watermark, LocalDateTime newest) {}
}
