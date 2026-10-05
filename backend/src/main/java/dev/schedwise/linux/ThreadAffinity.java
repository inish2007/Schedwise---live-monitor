package dev.schedwise.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** May adjust only the calling backend thread, never a browser-selected PID. */
public final class ThreadAffinity {
    private ThreadAffinity() {}

    public static SortedSet<Integer> parse(String list) {
        SortedSet<Integer> result = new TreeSet<>();
        if (list == null || list.length() > 32768) throw new IllegalArgumentException("Invalid CPU list");
        for (String part : list.strip().split(",")) {
            String[] range = part.split("-");
            int start = Integer.parseInt(range[0]);
            int end = range.length == 1 ? start : Integer.parseInt(range[1]);
            if (range.length > 2 || start < 0 || end < start || end > 65535 || end-start > 4096)
                throw new IllegalArgumentException("Invalid CPU range");
            for (int cpu = start; cpu <= end; cpu++) result.add(cpu);
            if (result.size() > 4096) throw new IllegalArgumentException("CPU count bound exceeded");
        }
        return result;
    }

    public static String current() throws IOException {
        return ProcParser.status(Files.readString(Path.of("/proc/thread-self/status"))).get("Cpus_allowed_list");
    }

    public static String setCurrent(String cpus) throws Exception {
        parse(cpus);
        String original = current();
        long tid = ProcParser.stat(Files.readString(Path.of("/proc/thread-self/stat"))).pid();
        Process process = new ProcessBuilder("/usr/bin/taskset", "-pc", cpus, Long.toString(tid))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Collector affinity change failed");
            if (!parse(current()).equals(parse(cpus))) throw new IOException("Collector affinity readback mismatch");
        } finally { process.destroy(); }
        return original;
    }
}
