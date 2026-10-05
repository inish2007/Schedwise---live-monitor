package dev.schedwise.linux;
import java.io.IOException;
import java.util.List;
public interface ProcSource {
 String read(String path) throws IOException;
 List<Long> pids() throws IOException;
 long nanoTime();
}
