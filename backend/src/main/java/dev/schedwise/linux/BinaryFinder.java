package dev.schedwise.linux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Fixed system directories only: never execute a browser-supplied path or search PATH. */
public final class BinaryFinder {
    private static final Set<String> APPROVED = Set.of("kill", "renice", "taskset", "getconf", "systemd-detect-virt", "python3");
    private BinaryFinder() {}
    public static Path find(String name) { return find(name, List.of(Path.of("/usr/bin"), Path.of("/bin"))); }
    public static Path find(String name, List<Path> directories) {
        if (!APPROVED.contains(name)) throw new IllegalArgumentException("Unapproved executable: " + name);
        for (Path directory : directories) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("Missing executable " + name + " in " + directories);
    }
}
