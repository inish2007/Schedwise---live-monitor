package dev.schedwise;

import dev.schedwise.gameshield.SystemProcessShield;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SystemProcessShieldTest {

    @Test
    void testCoreProcessImmunity() {
        // PID 1 is always immune
        assertTrue(SystemProcessShield.isImmune(1, "systemd", "/sbin/init", 0L, null));
        assertNotNull(SystemProcessShield.getImmunityReason(1, "systemd", "/sbin/init", 0L, null));

        // Kernel worker (empty cmdline)
        assertTrue(SystemProcessShield.isImmune(205, "kworker/u16:1", "", 0L, null));
        assertTrue(SystemProcessShield.getImmunityReason(205, "kworker/u16:1", "", 0L, null).contains("kernel worker"));

        // Root system daemon
        assertTrue(SystemProcessShield.isImmune(1500, "sshd", "/usr/sbin/sshd", 0L, null));
    }

    @Test
    void testDisplayAndAudioImmunity() {
        // Audio servers (PipeWire, WirePlumber, PulseAudio)
        assertTrue(SystemProcessShield.isImmune(2500, "pipewire", "/usr/bin/pipewire", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2501, "wireplumber", "/usr/bin/wireplumber", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2502, "pulseaudio", "/usr/bin/pulseaudio", 1000L, null));

        // Display compositors and window managers
        assertTrue(SystemProcessShield.isImmune(2600, "gnome-shell", "/usr/bin/gnome-shell", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2601, "Xorg", "/usr/lib/Xorg :0", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2602, "Xwayland", "/usr/bin/Xwayland", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2603, "kwin_wayland", "/usr/bin/kwin_wayland", 1000L, null));
        assertTrue(SystemProcessShield.isImmune(2604, "hyprland", "Hyprland", 1000L, null));
    }

    @Test
    void testCgroupSliceProtection() {
        // Services inside /system.slice/ are immune even if non-root
        assertTrue(SystemProcessShield.isImmune(3100, "custom-service", "/opt/app", 1000L, "0::/system.slice/custom.service"));

        // Desktop session slice
        assertTrue(SystemProcessShield.isImmune(3200, "session-mgr", "/usr/bin/mgr", 1000L, "0::/user.slice/user-1000.slice/session.slice/org.gnome.Session.service"));
    }

    @Test
    void testEligibleUserApplications() {
        // Regular user applications are NOT immune and eligible for Game Shield
        assertFalse(SystemProcessShield.isImmune(4000, "chrome", "/opt/google/chrome/chrome", 1000L, "0::/user.slice/user-1000.slice/app.slice"));
        assertFalse(SystemProcessShield.isImmune(4001, "discord", "/usr/share/discord/Discord", 1000L, null));
        assertFalse(SystemProcessShield.isImmune(4002, "python3", "python3 worker.py", 1000L, null));
        assertFalse(SystemProcessShield.isImmune(4003, "blender", "/usr/bin/blender", 1000L, null));
        assertFalse(SystemProcessShield.isImmune(4004, "qbittorrent", "qbittorrent", 1000L, null));
    }
}
