package dev.schedwise.gameshield;
import com.fasterxml.jackson.databind.*;
import dev.schedwise.linux.BinaryFinder;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Bounded JSON IPC. Guardian has a separate OS session and restores on pipe EOF. */
public final class GuardianBridge implements AutoCloseable {
 private final ObjectMapper mapper=new ObjectMapper();
 private final Path directory;
 private Process process; private BufferedWriter input;
 private final BlockingQueue<String> replies=new ArrayBlockingQueue<>(4);
 private String failure; private int restarts;
 public GuardianBridge(Path directory){this.directory=directory.toAbsolutePath();start();}
 private synchronized void start(){
  try {
   Files.createDirectories(directory);
   if(Files.isSymbolicLink(directory))throw new IOException("Recovery directory must not be a symlink");
   Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
   Path script=directory.resolve("shield-guardian.py");
   if(Files.isSymbolicLink(script))throw new IOException("Guardian script must not be a symlink");
   try(InputStream resource=getClass().getResourceAsStream("/shield/guardian.py")){if(resource==null)throw new IOException("Guardian resource missing");Files.copy(resource,script,StandardCopyOption.REPLACE_EXISTING);}
   Files.setPosixFilePermissions(script,PosixFilePermissions.fromString("rw-------"));
   Path log=directory.resolve("shield-guardian.log");
   if(Files.isSymbolicLink(log))throw new IOException("Unsafe guardian log");
   process=new ProcessBuilder(BinaryFinder.find("python3").toString(),"-u",script.toString(),"--data",directory.toString())
    .redirectError(log.toFile()).start();
   input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
   Process child=process;
   Thread reader=new Thread(()->{
    try(var stream=new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)){
     StringBuilder line=new StringBuilder();int ch;
     while((ch=stream.read())!=-1){if(ch=='\n'){if(!replies.offer(line.toString()))break;line.setLength(0);}else {line.append((char)ch);if(line.length()>131072)break;}}
    }catch(IOException ignored){}
   },"shield-guardian-replies");reader.setDaemon(true);reader.start();
   String ready=replies.poll(5,TimeUnit.SECONDS);if(ready==null)throw new IOException("Guardian startup failed; inspect local guardian log");
   mapper.readTree(ready);failure=null;
  }catch(Exception e){failure=e.getMessage();if(input!=null)try{input.close();if(process!=null)process.waitFor(2,TimeUnit.SECONDS);}catch(Exception ignored){} }
 }
 public synchronized ShieldState state(){
  if(process!=null&&!process.isAlive()&&restarts++<2){failure="Guardian exited; attempting journal recovery";replies.clear();start();}
  if(failure!=null)return ShieldState.unavailable(failure);
  try{
   Path journal=directory.resolve("shield-state.json");
   if(Files.isSymbolicLink(journal)||Files.size(journal)>131072)throw new IOException("Invalid recovery journal");
   return mapper.readValue(Files.readString(journal),ShieldState.class);
  }catch(Exception e){return ShieldState.unavailable("Recovery state unavailable: "+e.getMessage());}
 }
 public synchronized ShieldState command(Map<String,Object> request){
  if(failure!=null||process==null||!process.isAlive())throw new IllegalStateException("Guardian unavailable; retry connection to reconcile recovery");
  try{
   input.write(mapper.writeValueAsString(request));input.newLine();input.flush();
   String reply=replies.poll(8,TimeUnit.SECONDS);
   if(reply==null){input.close();failure="Guardian response timed out; pipe closed for recovery";throw new IllegalStateException(failure);}
   JsonNode result=mapper.readTree(reply);
   if(result.has("error"))throw new IllegalArgumentException(result.get("error").asText());
   return mapper.treeToValue(result.get("state"),ShieldState.class);
  }catch(IllegalArgumentException|IllegalStateException e){throw e;}catch(Exception e){throw new IllegalStateException("Guardian communication failed; inspect recovery state",e);}
 }
 public long pid(){return process==null?-1:process.pid();}
 public synchronized void close(){if(input!=null)try{input.close();if(process!=null)process.waitFor(2,TimeUnit.SECONDS);}catch(Exception ignored){} }
}
