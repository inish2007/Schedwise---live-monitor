package dev.schedwise.gameshield;

import java.util.Set;

/**
 * System Essentials Protection Shield.
 * Enforces multi-layer immunity to ensure operating system infrastructure,
 * display compositors, audio servers, kernel workers, and session managers
 * are NEVER frozen, deprioritized, or killed.
 */
public final class SystemProcessShield {
    private SystemProcessShield() {}

    // Hardcoded set of known Linux desktop, audio, graphics, and system daemons
    private static final Set<String> IMMUNE_PROCESS_NAMES = Set.of(
            // Init and Core OS
            "systemd", "init", "systemd-journal", "systemd-udevd", "systemd-logind",
            "dbus-daemon", "dbus-broker", "polkitd", "upowerd", "accounts-daemon",
            "cron", "crond", "atd", "sshd", "rsyslogd",

            // Display Managers, Compositors, and Window Managers
            "xorg", "xwayland", "gnome-shell", "gnome-session", "gdm", "gdm3",
            "kwin_wayland", "kwin_x11", "plasmashell", "sddm", "lightdm",
            "mutter", "sway", "hyprland", "weston", "wayfire", "labwc",

            // Audio Subsystem (Crucial: prevents audio crackling/stutter in games)
            "pipewire", "wireplumber", "pipewire-pulse", "pulseaudio", "jackd",

            // Input and Hardware
            "libinput", "colord", "bluetoothd", "wpa_supplicant", "networkmanager",

            // GPU and Performance Daemons
            "nvidia-smi", "nvidia-powerd", "gamemoded", "power-profiles-daemon",

            // Desktop Portals and Session Services
            "xdg-desktop-portal", "xdg-desktop-portal-gnome", "xdg-desktop-portal-kde",
            "xdg-document-portal", "xdg-permission-store",

            // Game Launchers & Wine/Proton Drivers (protect runtime infrastructure)
            "steam", "steamwebhelper", "heroic", "lutris", "wine64", "wineserver",
            "gamescope"
    );

    /**
     * Determines whether a process is immune to suspension or deprioritization.
     */
    public static boolean isImmune(long pid, String name, String cmdline, Long uid, String cgroup) {
        return getImmunityReason(pid, name, cmdline, uid, cgroup) != null;
    }

    /**
     * Returns the human-readable reason why a process is immune, or null if eligible for optimization.
     */
    public static String getImmunityReason(long pid, String name, String cmdline, Long uid, String cgroup) {
        // 1. PID <= 100 is always core kernel or init
        if (pid <= 100) {
            return "Core system process (PID <= 100)";
        }

        // 2. Kernel threads have empty cmdline
        if (cmdline == null || cmdline.isBlank()) {
            return "Linux kernel worker thread (empty cmdline)";
        }

        // 3. Root processes (UID 0) are system infrastructure
        if (uid != null && uid == 0) {
            return "Root system daemon (UID 0)";
        }

        // 4. Exact binary name match in immunity set
        if (name != null) {
            String lowerName = name.toLowerCase().trim();
            if (IMMUNE_PROCESS_NAMES.contains(lowerName)) {
                return "Protected system/desktop infrastructure (" + name + ")";
            }
        }

        // 5. Command line prefix inspection
        if (cmdline != null) {
            String lowerCmd = cmdline.toLowerCase();
            for (String immuneName : IMMUNE_PROCESS_NAMES) {
                if (lowerCmd.contains(immuneName)) {
                    return "Command line references protected service (" + immuneName + ")";
                }
            }
        }

        // 6. Cgroup slice protection
        if (cgroup != null) {
            if (cgroup.contains("/system.slice/")) {
                return "Operating system service (/system.slice)";
            }
            if (cgroup.contains("/session.slice/")) {
                return "Desktop session infrastructure (/session.slice)";
            }
        }

        return null;
    }
}
