package dev.schedwise.gameshield;
import dev.schedwise.linux.*;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.monitor.Capabilities;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.file.Path;

@Component
public class ShieldController {
    public record Selection(Identity identity,int expectedNice) {}
    private final LinuxSource linux;
    private final Capabilities capabilities;
    private final Set<Long> ancestors=new HashSet<>();
    private final long currentUid;
    private final GuardianBridge guardian;
    public ShieldController(LinuxSource linux,Capabilities capabilities){
        this.linux=linux;this.capabilities=capabilities;
        long uid=-1;try{uid=uid("/proc/self");}catch(Exception ignored){}currentUid=uid;
        ProcessHandle p=ProcessHandle.current();ancestors.add(p.pid());
        while(p.parent().isPresent()){p=p.parent().get();ancestors.add(p.pid());}
        guardian=new GuardianBridge(Path.of(System.getProperty("schedwise.data","../data")));
    }
    private long uid(String base)throws Exception {return Long.parseLong(ProcParser.status(linux.read(base+"/status")).get("Uid").split("\\s+")[0]);}
    private Identity identity(ProcParser.Stat stat){return new Identity(capabilities.bootId,stat.pid(),stat.startTicks());}
    private ProcParser.Stat verify(Identity id)throws Exception {
        if(id==null||id.pid()<=1||(ancestors.contains(id.pid())||id.pid()==guardian.pid())||currentUid<0||capabilities.bootId==null)throw new IllegalArgumentException("Invalid target or backend/ancestor process");
        ProcParser.Stat stat=ProcParser.stat(linux.read("/proc/"+id.pid()+"/stat"));
        if(!identity(stat).equals(id))throw new IllegalArgumentException("Process identity changed: "+id.pid());
        if(Set.of("Z","X","x").contains(stat.state()))throw new IllegalArgumentException("Process exited: "+id.pid());
        if(uid("/proc/"+id.pid())!=currentUid)throw new IllegalArgumentException("Target is not owned by the backend user");
        String reason=SystemProcessShield.getImmunityReason(id.pid(),stat.name(),stat.name(),currentUid,linux.read("/proc/"+id.pid()+"/cgroup"));
        if(reason!=null)throw new IllegalArgumentException("Protected process: "+reason);
        return stat;
    }
    public synchronized ShieldState getState(){return guardian.state();}
    public List<ShieldCandidate> scanCandidates(){
        List<ShieldCandidate> candidates=new ArrayList<>();
        try {for(long pid:linux.pids().stream().limit(4096).toList()){
            try {
                ProcParser.Stat stat=ProcParser.stat(linux.read("/proc/"+pid+"/stat"));
                if(Set.of("Z","X","x").contains(stat.state()))continue;
                Map<String,String> status=ProcParser.status(linux.read("/proc/"+pid+"/status"));
                long uid=Long.parseLong(status.get("Uid").split("\\s+")[0]);
                Long rss=null;
                try{rss=Math.multiplyExact(Long.parseLong(status.get("VmRSS").split("\\s+")[0]),1024L);}catch(Exception ignored){}
                String reason=SystemProcessShield.getImmunityReason(pid,stat.name(),stat.name(),uid,linux.read("/proc/"+pid+"/cgroup"));
                if((ancestors.contains(pid)||pid==guardian.pid()))reason="Backend or ancestor";
                candidates.add(new ShieldCandidate(pid,stat.name(),stat.name(),uid,stat.nice(),stat.state(),null,rss,reason!=null,
                    reason!=null?"SYSTEM_ESSENTIAL":uid==currentUid?"USER_APPLICATION":"OTHER_USER_PROCESS",reason,identity(stat),stat.threads()));
            }catch(Exception ignored){/* Exited, inaccessible, or inconsistent: omit this observation. */}
        }}catch(Exception e){throw new IllegalStateException("Process scan unavailable",e);}
        candidates.sort(Comparator.comparing((ShieldCandidate c)->c.rssBytes()==null?-1L:c.rssBytes()).reversed());
        return List.copyOf(candidates);
    }
    public synchronized ShieldState activate(Identity target,List<Selection> selections,int seconds,String operationId,String ownerId){
        uuid(operationId);uuid(ownerId);
        if(seconds<60||seconds>1800)throw new IllegalArgumentException("Duration must be 1–30 minutes");
        if(target==null||selections==null||selections.isEmpty()||selections.size()>16)throw new IllegalArgumentException("Select a target and 1–16 background processes");
        ShieldState current=guardian.state();
        // Guardian validates replay payload; don't re-preflight an already paused selection.
        if(!operationId.equals(current.operationId())){
            if(current.active())throw new IllegalStateException("Shield active or recovery pending");
            try{
                verify(target);Set<Long> seen=new HashSet<>();seen.add(target.pid());
                for(Selection selection:selections){
                    if(selection==null||selection.identity()==null||!seen.add(selection.identity().pid()))throw new IllegalArgumentException("Duplicate or invalid selection");
                    var st=verify(selection.identity());
                    if(st.nice()!=selection.expectedNice()||Set.of("T","t").contains(st.state()))throw new IllegalArgumentException("Selection changed; rescan before confirming");
                }
            }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Selection unavailable; rescan before confirming",e);}
        }
        return guardian.command(Map.of("action","activate","targetIdentity",target,"targets",selections,"maxSeconds",seconds,"operationId",operationId,"ownerId",ownerId));
    }
    public synchronized ShieldState deactivate(String sessionId,String operationId){
        uuid(sessionId);uuid(operationId);
        return guardian.command(Map.of("action","stop","sessionId",sessionId,"operationId",operationId));
    }
    public synchronized ShieldState heartbeat(String sessionId,String ownerId){
        uuid(sessionId);uuid(ownerId);
        return guardian.command(Map.of("action","heartbeat","sessionId",sessionId,"ownerId",ownerId));
    }
    private static void uuid(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("Valid operation, session and owner IDs are required");}}
    @PreDestroy public void shutdown(){guardian.close();}
}
