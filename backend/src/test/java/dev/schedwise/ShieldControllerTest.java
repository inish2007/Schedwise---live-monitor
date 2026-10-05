package dev.schedwise;
import dev.schedwise.gameshield.*;
import dev.schedwise.linux.*;
import dev.schedwise.monitor.Capabilities;
import dev.schedwise.model.Telemetry.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class ShieldControllerTest {
 @TempDir Path dir;
 @Test void rejectsStaleAndUnconfirmedRequestsThenControlsOnlySelectedChild()throws Exception{
  System.setProperty("schedwise.data",dir.toString());
  var linux=new LinuxSource();var caps=new Capabilities(linux);var c=new ShieldController(linux,caps);
  Process target=new ProcessBuilder("/bin/sleep","20").start(),worker=new ProcessBuilder("/bin/sleep","20").start(),other=new ProcessBuilder("/bin/sleep","20").start();
  try{
   var ts=ProcParser.stat(linux.read("/proc/"+target.pid()+"/stat"));var ws=ProcParser.stat(linux.read("/proc/"+worker.pid()+"/stat"));
   var ti=new Identity(caps.bootId,target.pid(),ts.startTicks());var wi=new Identity(caps.bootId,worker.pid(),ws.startTicks());
   String op=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();
   assertTrue(c.getState().guardianAvailable());assertFalse(c.getState().active());
   assertThrows(IllegalArgumentException.class,()->c.activate(ti,List.of(),60,op,owner));
   assertThrows(IllegalArgumentException.class,()->c.activate(ti,List.of(new ShieldController.Selection(wi,0)),1801,op,owner));
   assertThrows(IllegalArgumentException.class,()->c.activate(ti,List.of(new ShieldController.Selection(new Identity(caps.bootId,worker.pid(),wi.startTicks()+1),0)),60,op,owner));
   var selections=List.of(new ShieldController.Selection(wi,ws.nice()));
   assertEquals("ON",c.activate(ti,selections,60,op,owner).status());
   assertEquals("ON",c.activate(ti,selections,60,op,owner).status());
   assertEquals("T",ProcParser.stat(linux.read("/proc/"+worker.pid()+"/stat")).state());
   assertNotEquals("T",ProcParser.stat(linux.read("/proc/"+other.pid()+"/stat")).state());
   assertThrows(IllegalArgumentException.class,()->c.heartbeat(op,UUID.randomUUID().toString()));
   assertEquals("ON",c.heartbeat(op,owner).status());
   assertEquals("OFF",c.deactivate(op,UUID.randomUUID().toString()).status());
   assertEquals("OFF",c.deactivate(op,UUID.randomUUID().toString()).status());
   assertNotEquals("T",ProcParser.stat(linux.read("/proc/"+worker.pid()+"/stat")).state());
   assertNull(c.getState().estimatedCpuFreed());
  }finally{c.shutdown();for(Process p:List.of(target,worker,other)){p.destroyForcibly();p.waitFor();}System.clearProperty("schedwise.data");}
 }
}
