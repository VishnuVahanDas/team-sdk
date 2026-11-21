package org.ajeet.device;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One punch event read from the device log. */
public record Punch(int enrollNumber, int verifyMode, LocalDateTime time) {

    /** Human-readable verify method + status, stored as punches.verify_label. */
    public String verifyLabel() {
        return label(verifyMode);
    }

    /**
     * Decodes the SBXPC packed verify mode (see the vendor sample's
     * GLogInfo.verify_mode): low byte = verify method, next byte = attendance
     * status, upper 16 bits = anti-passback. E.g. 407 = 0x197 = fingerprint
     * (151) + status 1 (check-out).
     */
    public static String label(int verifyMode) {
        int vm = verifyMode & 0xFF;
        String method = method(vm);
        if (method == null) return "Mode " + verifyMode;
        boolean attendance = (1 <= vm && vm <= 7) || (30 <= vm && vm <= 34)
                || (51 <= vm && vm <= 53) || (101 <= vm && vm <= 103) || (151 <= vm && vm <= 153);
        if (!attendance) return method; // door / alarm events carry no status
        String status = switch ((verifyMode >> 8) & 0xFF) {
            case 0 -> "Check-in";
            case 1 -> "Check-out";
            case 2 -> "OT check-in";
            case 3 -> "OT check-out";
            case 4 -> "Return";
            case 5 -> "Go out";
            default -> null;
        };
        return status == null ? method : method + " · " + status;
    }

    private static String method(int vm) {
        // 51-53, 101-103, 151-153 are the same methods as 1-3 (device variants)
        if (vm > 50 && vm < 200 && vm % 50 >= 1 && vm % 50 <= 3) vm = vm % 50;
        return switch (vm) {
            case 1 -> "Fingerprint";
            case 2 -> "Password";
            case 3 -> "Card";
            case 4 -> "Fingerprint+Card";
            case 5 -> "Fingerprint+Password";
            case 6 -> "Card+Password";
            case 7 -> "Fingerprint+Card+Password";
            case 10 -> "Hand lock";
            case 11 -> "Prog lock";
            case 12 -> "Prog open";
            case 13 -> "Prog close";
            case 14 -> "Auto recover";
            case 20 -> "Lock over";
            case 21 -> "Illegal open";
            case 22 -> "Duress alarm";
            case 23 -> "Tamper detect";
            case 30 -> "Face";
            case 31 -> "Face+Card";
            case 32 -> "Face+Password";
            case 33 -> "Face+Card+Password";
            case 34 -> "Face+Fingerprint";
            default -> null;
        };
    }

    private static final Pattern LEGACY_MODE = Pattern.compile("Mode (-?\\d+)");

    /**
     * Raw verify mode behind a label written by the old decoder (before the
     * packed format was understood), or null if {@code label} isn't one.
     */
    public static Integer legacyCode(String label) {
        Matcher m = LEGACY_MODE.matcher(label);
        if (m.matches()) return Integer.parseInt(m.group(1));
        return switch (label) {
            case "Fingerprint" -> 1;
            case "Password" -> 2;
            case "Card" -> 3;
            case "Fingerprint+Card" -> 4;
            case "Fingerprint+Password" -> 5;
            case "Card+Fingerprint" -> 6;
            case "Face" -> 7;
            case "Vein" -> 11;
            case "Palm" -> 15;
            default -> null;
        };
    }
}
