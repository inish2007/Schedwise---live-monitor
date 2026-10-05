package dev.schedwise.monitor;
import dev.schedwise.linux.*;
import dev.schedwise.model.Telemetry.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
@Component
public class Collector {
 private final ProcSource source; private final Capabilities caps;
 private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
 private final ArrayDeque<Snapshot> ring=new ArrayDeque<>();
 private final Map<Identity,Previous> previous=new HashMap<>();
 private Map<String,List<Long>> cpuPrevious=Map.of();private long cpuTime;private long sequence;
 private volatile Snapshot latest;private volatile long publishedNanos;
 private record Previous(ProcParser.Stat stat,long nanos,ProcessSample sample){}
 @org.springframework.beans.factory.annotation.Autowired
 public Collector(LinuxSource source,Capabilities caps){this.source=source;this.caps=caps;}
 public Collector(ProcSource source,Capabilities caps){this.source=source;this.caps=caps;}
 @PostConstruct public void start(){executor.scheduleWithFixedDelay(this::collect,0,1,TimeUnit.SECONDS);}
 @PreDestroy public void stop(){executor.shutdownNow();}
 public Future<String> observerAffinity(String cpus) {
  return executor.submit(() -> cpus == null ? ThreadAffinity.current() : ThreadAffinity.setCurrent(cpus));
 }
 public Snapshot latest(){return latest;}
 public long age(){return latest==null?-1:Math.max(0,(System.nanoTime()-publishedNanos)/1_000_000);}
 public synchronized List<Snapshot> after(long seq){return ring.stream().filter(s->s.sequence()>seq).toList();}
 private <T> Value<T> v(T x,String unit,Kind kind,String path,Instant at,Double seconds,String reason){return caps.value(x,unit,kind,path,at,seconds,reason);}
 public void collect(){
  long begin=source.nanoTime();Instant now=Instant.now();Map<String,Cpu> cpus=new LinkedHashMap<>();
  List<ProcessSample> samples=new ArrayList<>();Map<Identity,Previous> next=new HashMap<>();
  int unreadable=0,omitted=0;String failure=null;
  try{
   Map<String,List<Long>> counters=ProcParser.cpus(source.read("/proc/stat"));
   double seconds=(begin-cpuTime)/1e9;
   for(var e:counters.entrySet()){
    Double busy=null,steal=null;String reason=null;
    try{if(seconds<=0)throw new IllegalArgumentException("INVALID_INTERVAL");double[] d=ProcParser.cpuDelta(cpuPrevious.get(e.getKey()),e.getValue());busy=d[0];steal=d[1];}catch(IllegalArgumentException ex){reason=ex.getMessage();}
    cpus.put(e.getKey(),new Cpu(v(busy,"% capacity",Kind.DERIVED,"/proc/stat:"+e.getKey(),now,cpuTime==0?null:seconds,reason),v(steal,"% capacity",Kind.DERIVED,"/proc/stat:"+e.getKey(),now,cpuTime==0?null:seconds,reason),v(e.getValue(),"ticks",Kind.MEASURED,"/proc/stat:"+e.getKey(),now,null,null)));
   }cpuPrevious=counters;cpuTime=begin;
  }catch(Exception e){failure="CPU_SOURCE_UNAVAILABLE";cpuPrevious=Map.of();cpuTime=0;}
  try{
   List<Long> pids=source.pids();omitted=Math.max(0,pids.size()-4096);
   Set<Long> seen=new HashSet<>();
   for(long pid:pids.stream().limit(4096).toList()){
    seen.add(pid);String base="/proc/"+pid;Identity id=null;
    try{
     ProcParser.Stat st=ProcParser.stat(source.read(base+"/stat"));long stamp=source.nanoTime();
     if(st.pid()!=pid||caps.bootId==null)throw new IllegalArgumentException("IDENTITY_UNAVAILABLE");
     id=new Identity(caps.bootId,pid,st.startTicks());
     Map<String,String> status=Map.of();String statusReason=null;
     try{status=ProcParser.status(source.read(base+"/status"));}catch(Exception e){statusReason="STATUS_INACCESSIBLE";}
     ProcParser.Stat check=ProcParser.stat(source.read(base+"/stat"));
     if(check.pid()!=pid||check.startTicks()!=st.startTicks())throw new IllegalArgumentException("IDENTITY_CHANGED_DURING_READ");
     Previous old=previous.get(id);Double pct=null,seconds=null;String why=null;
     if(old==null)why=previous.keySet().stream().anyMatch(k->k.pid()==pid)?"IDENTITY_CHANGED":"FIRST_SAMPLE";
     else {seconds=(stamp-old.nanos())/1e9;try{pct=ProcParser.processPercent(old.stat().ticks(),st.ticks(),caps.clockTicks,seconds);}catch(IllegalArgumentException e){why=e.getMessage();}}
     Long uid=null;try{uid=Long.parseLong(status.get("Uid").split("\\s+")[0]);}catch(Exception e){statusReason="STATUS_FIELD_UNAVAILABLE";}
     String affinity=status.get("Cpus_allowed_list");
     ProcessSample p=new ProcessSample(id,v(st.name(),"name",Kind.MEASURED,base+"/stat",now,null,null),v(st.state(),"state",Kind.MEASURED,base+"/stat",now,null,null),v(uid,"UID",Kind.MEASURED,base+"/status",now,null,uid==null?statusReason:null),v(st.nice(),"nice",Kind.MEASURED,base+"/stat",now,null,null),v(st.threads(),"threads",Kind.MEASURED,base+"/stat",now,null,null),v(affinity,"CPU list",Kind.MEASURED,base+"/status",now,null,affinity==null?"STATUS_FIELD_UNAVAILABLE":null),v(pct,"% one CPU",Kind.DERIVED,base+"/stat",now,seconds,why),"OBSERVE_ONLY","PRESENT");
     samples.add(p);next.put(id,new Previous(st,stamp,p));
    }catch(Exception e){unreadable++;}
   }
   for(var e:previous.entrySet())if(!next.containsKey(e.getKey())){
    ProcessSample p=e.getValue().sample();String why=seen.contains(p.identity().pid())?"INACCESSIBLE_OR_IDENTITY_CHANGED":omitted>0?"NOT_OBSERVED_TRUNCATED_SCAN":"EXITED";
    // Preserve identity, never present old attributes as current measurements.
    samples.add(new ProcessSample(p.identity(),v(null,"name",Kind.MEASURED,"/proc",now,null,why),v(null,"state",Kind.MEASURED,"/proc",now,null,why),v(null,"UID",Kind.MEASURED,"/proc",now,null,why),v(null,"nice",Kind.MEASURED,"/proc",now,null,why),v(null,"threads",Kind.MEASURED,"/proc",now,null,why),v(null,"CPU list",Kind.MEASURED,"/proc",now,null,why),v(null,"% one CPU",Kind.DERIVED,"/proc",now,null,why),"OBSERVE_ONLY",why));
   }
  }catch(Exception e){failure="PROCESS_SOURCE_UNAVAILABLE";}
  previous.clear();previous.putAll(next);
  Value<String> psi;
  try{String some=source.read("/proc/pressure/cpu").lines().filter(s->s.startsWith("some ")).findFirst().orElseThrow();psi=v(some,"kernel PSI some averages (%) and total (us)",Kind.MEASURED,"/proc/pressure/cpu",now,null,null);}catch(Exception e){psi=v(null,"PSI some",Kind.MEASURED,"/proc/pressure/cpu",now,null,"MISSING_OR_INACCESSIBLE");}
  Value<String> io;
  try{String some=source.read("/proc/pressure/io").lines().filter(line->line.startsWith("some ")).findFirst().orElseThrow();io=v(some,"kernel PSI some averages (%) and total (us)",Kind.MEASURED,"/proc/pressure/io",now,null,null);}catch(Exception e){io=v(null,"PSI some",Kind.MEASURED,"/proc/pressure/io",now,null,"MISSING_OR_INACCESSIBLE");}
  Snapshot s=new Snapshot(caps.sessionId,++sequence,now,caps.environmentId,failure==null?"AVAILABLE":"PARTIAL_OR_UNAVAILABLE",failure,Collections.unmodifiableMap(cpus),List.copyOf(samples),psi,io,omitted,unreadable,(source.nanoTime()-begin)/1e6);
  synchronized(this){ring.addLast(s);while(ring.size()>120||(!ring.isEmpty()&&ring.peekFirst().timestamp().isBefore(now.minusSeconds(120))))ring.removeFirst();}
  publishedNanos=System.nanoTime();latest=s;
 }
}
