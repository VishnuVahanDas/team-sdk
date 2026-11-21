package org.ajeet.device;

import org.ajeet.config.Env;

import java.nio.file.Path;

/** Builds a {@link DeviceClient} from DEVICE_* settings (env or .env). */
public final class DeviceConfig {

    /** Folder with SBXPCDLL64.dll + SBPCCOMM64.dll, relative to the working directory. */
    private static final String DEFAULT_DLL_DIR = "native";

    private DeviceConfig() {}

    /** Returns null (sync disabled) when DEVICE_PASSWORD (comm key) is unset. */
    public static DeviceClient fromEnv() {
        String password = Env.get("DEVICE_PASSWORD", null);
        if (password == null || password.isBlank()) {
            System.out.println("DEVICE_PASSWORD not set -> device sync disabled");
            return null;
        }
        DeviceClient device = new DeviceClient(
                Env.get("DEVICE_IP", "192.168.31.120"),
                Integer.parseInt(Env.get("DEVICE_PORT", "5005")),
                Integer.parseInt(password),
                Integer.parseInt(Env.get("DEVICE_MACHINE", "1")),
                dllDir(),
                Path.of("device-sync-state.txt"));
        System.out.println("Device sync enabled: " + device.describe());
        return device;
    }

    private static Path dllDir() {
        return Path.of(Env.get("DEVICE_DLL_DIR", DEFAULT_DLL_DIR));
    }
}
