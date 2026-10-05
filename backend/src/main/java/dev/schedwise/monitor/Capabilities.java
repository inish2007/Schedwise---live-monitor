package dev.schedwise.monitor;
import dev.schedwise.linux.*;
import dev.schedwise.model.Telemetry.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
@Component
public class Capabilities {
 public final String sessionId=UUID.randomUUID().toString();
 public final String environmentId=sessionId+":linux";
 private final LinuxSource source;
 public final String bootId;
 public final long clockTicks;
 public Capabilities(LinuxSource source){
  this.source=source;String boot=null;long hz=0;
  try{boot=source.read("/proc/sys/kernel/random/boot_id");}catch(Exception ignored){}
  try{hz=Long.parseLong(source.command(BinaryFinder.find("getconf").toString(),"CLK_TCK"));}catch(Exception ignored){}
  bootId=boot;clockTicks=hz;
 }
 public <T> Value<T> value(T v,String unit,Kind kind,String src,Instant at,Double window,String reason){
  return new Value<>(v,unit,kind,src,at,sessionId,v==null?"UNAVAILABLE":"AVAILABLE",reason,window);
 }
 private Value<String> probe(String path){try{return value(source.read(path),"text",Kind.MEASURED,path,Instant.now(),null,null);}catch(Exception e){return value(null,"text",Kind.MEASURED,path,Instant.now(),null,"MISSING_OR_INACCESSIBLE");}}
 public Map<String,Value<?>> discover(){
  Map<String,Value<?>> m=new LinkedHashMap<>();
  m.put("kernel",probe("/proc/sys/kernel/osrelease"));
  String kernel=System.getProperty("os.version");
  String scope=kernel.toLowerCase().contains("microsoft")?"WSL Linux scope (WSL version not independently verified)":"Visible Linux proc scope; host coverage not guaranteed";
  try{scope+="; virtualization: "+source.command(BinaryFinder.find("systemd-detect-virt").toString());}catch(Exception e){scope+="; virtualization not detected or probe unavailable";}
  m.put("scope",value(scope,"text",Kind.DERIVED,"environment probes",Instant.now(),null,null));
  m.put("bootId",value(bootId,"id",Kind.MEASURED,"/proc/sys/kernel/random/boot_id",Instant.now(),null,bootId==null?"INACCESSIBLE":null));
  m.put("clockTicks",value(clockTicks>0?clockTicks:null,"ticks/s",Kind.MEASURED,"getconf CLK_TCK",Instant.now(),null,clockTicks<=0?"UNAVAILABLE":null));
  try{m.put("allowedCpus",value(ProcParser.status(source.read("/proc/self/status")).get("Cpus_allowed_list"),"CPU list",Kind.MEASURED,"/proc/self/status",Instant.now(),null,null));}catch(Exception e){m.put("allowedCpus",probe("/proc/self/status"));}
  m.put("cgroupMembership",probe("/proc/self/cgroup"));
  // Report every visible ancestor: a child 'max' does not override a tighter parent quota.
  try{
   String member=source.read("/proc/self/cgroup").lines().filter(s->s.startsWith("0::")).findFirst().orElseThrow().substring(3);
   Path base=Path.of("/sys/fs/cgroup"), p=base.resolve(member.replaceFirst("^/", "")).normalize();
   if(!p.startsWith(base))throw new IllegalArgumentException();
   int count=0;while(p.startsWith(base)&&count++<64){m.put("cpu.max "+base.relativize(p),probe(p.resolve("cpu.max").toString()));if(p.equals(base))break;p=p.getParent();}
  }catch(Exception e){m.put("cgroupCpuLimits",value(null,"quota/period",Kind.MEASURED,"cgroup",Instant.now(),null,"CGROUP_V2_PATH_UNAVAILABLE; v1 quota discovery unsupported"));}
  m.put("cpuPressure",probe("/proc/pressure/cpu"));
  m.put("schedstatAccounting",probe("/proc/sys/kernel/sched_schedstats"));
  m.put("schedstatSource",probe("/proc/self/schedstat"));
  m.put("autogroup",probe("/proc/self/autogroup"));
  for(String name:List.of("taskset","renice","python3")){String executable=null;try{executable=BinaryFinder.find(name).toString();}catch(IllegalStateException ignored){}m.put(name,value(executable,"capability",Kind.MEASURED,"fixed system executable discovery",Instant.now(),null,executable==null?"Executable unavailable":"Executable discovery does not establish control permission"));}
  try{m.put("pidNamespace",value(Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString(),"namespace",Kind.MEASURED,"/proc/self/ns/pid",Instant.now(),null,null));}catch(Exception ignored){}
  return Collections.unmodifiableMap(m);
 }
}
