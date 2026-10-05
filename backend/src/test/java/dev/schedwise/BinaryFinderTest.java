package dev.schedwise;
import dev.schedwise.linux.BinaryFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class BinaryFinderTest {
 @TempDir Path dir;
 @Test void fallsBackOnlyToExecutableApprovedFile()throws Exception{
  Path first=Files.createDirectory(dir.resolve("usr")),second=Files.createDirectory(dir.resolve("bin"));
  Files.writeString(first.resolve("renice"),"test");Path fallback=Files.writeString(second.resolve("renice"),"test");fallback.toFile().setExecutable(true);
  assertEquals(fallback,BinaryFinder.find("renice",List.of(first,second)));
  assertThrows(IllegalStateException.class,()->BinaryFinder.find("kill",List.of(first,second)));
  assertThrows(IllegalArgumentException.class,()->BinaryFinder.find("../renice",List.of(first,second)));
 }
}
