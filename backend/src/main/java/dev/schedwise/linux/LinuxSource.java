package dev.schedwise.linux;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
@Component
public class LinuxSource implements ProcSource {
 public String read(String path) throws IOException {
  try(var in=Files.newInputStream(Path.of(path))) {
   byte[] data=in.readNBytes(262145);
   if(data.length>262144) throw new IOException("Source exceeds read bound");
   return new String(data,StandardCharsets.UTF_8).strip();
  }
 }
 public List<Long> pids() throws IOException {
  try(var paths=Files.list(Path.of("/proc"))) {
   return paths.map(p->p.getFileName().toString()).filter(s->s.matches("[0-9]+"))
      .map(Long::parseLong).sorted().limit(4097).toList();
  }
 }
 public long nanoTime(){return System.nanoTime();}
 public String command(String... args) throws IOException {
  if(!Set.of("/usr/bin/getconf","/usr/bin/systemd-detect-virt","/bin/getconf","/bin/systemd-detect-virt").contains(args[0])) throw new IOException("Unapproved command");
  Process p=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  try {
   if(!p.waitFor(2,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Probe timeout");}
   if(p.exitValue()!=0) throw new IOException("Probe unavailable or none detected");
   byte[] bytes=p.getInputStream().readNBytes(4097);
   if(bytes.length>4096) throw new IOException("Probe output too large");
   return new String(bytes,StandardCharsets.UTF_8).strip();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted",e);}
  finally {p.destroy();}
 }
}
