package org.ajeet.device;

import java.time.LocalDateTime;

/** One punch event read from the device log. */
public record Punch(int enrollNumber, int verifyMode, LocalDateTime time) {

    /** Human-readable verify method, stored as punches.verify_label. */
    public String verifyLabel() {
        return switch (verifyMode) {
            case 1 -> "Fingerprint";
            case 2 -> "Password";
            case 3 -> "Card";
            case 4 -> "Fingerprint+Card";
            case 5 -> "Fingerprint+Password";
            case 6 -> "Card+Fingerprint";
            case 7 -> "Face";
            case 11 -> "Vein";
            case 15 -> "Palm";
            default -> "Mode " + verifyMode;
        };
    }
}
