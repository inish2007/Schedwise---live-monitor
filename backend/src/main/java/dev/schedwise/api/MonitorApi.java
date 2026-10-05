package dev.schedwise.api;
import dev.schedwise.monitor.*;
import dev.schedwise.persistence.SessionStore;
import dev.schedwise.model.Telemetry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.*;
import java.nio.charset.StandardCharsets;
@RestController
@RequestMapping("/api")
public class MonitorApi {
 private final dev.schedwise.experiment.ExperimentManager experiments;
 private final Collector collector;private final Capabilities caps;private final SessionStore store;private final ObjectMapper json;
 public MonitorApi(Collector collector,Capabilities caps,SessionStore store,ObjectMapper json,dev.schedwise.experiment.ExperimentManager experiments){this.experiments=experiments;this.collector=collector;this.caps=caps;this.store=store;this.json=json;}
 @GetMapping("/capabilities") public Map<String,Object> capabilities(){return Map.of("sessionId",caps.sessionId,"environmentId",caps.environmentId,"sources",caps.discover(),"storage",store.status(),"intervalMillis",1000,"retentionSeconds",120,"processLimit",4096);}
 @GetMapping("/snapshots/latest") public Latest latest(){return new Latest(collector.latest(),collector.age(),store.status());}
 @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 public ResponseEntity<StreamingResponseBody> events(@RequestHeader(value="Last-Event-ID",required=false) String cursor){
  if(cursor!=null&&!cursor.matches("[a-zA-Z0-9-]{1,64}:[0-9]{1,18}"))return ResponseEntity.badRequest().build();
  StreamingResponseBody body=out->{
   long seq=0;boolean reset=false;
   if(cursor!=null){String[] parts=cursor.split(":");if(parts[0].equals(caps.sessionId))seq=Long.parseLong(parts[1]);else reset=true;}
   if(collector.latest()!=null&&seq>collector.latest().sequence()){seq=0;reset=true;}
   long until=System.nanoTime()+TimeUnitSeconds(25);
   while(System.nanoTime()<until){
    var rows=collector.after(seq);
    if(!rows.isEmpty()){
     if(reset||rows.getFirst().sequence()>seq+1){out.write("event: gap\ndata: {\"reason\":\"SESSION_CHANGED_OR_HISTORY_EXPIRED\"}\n\n".getBytes(StandardCharsets.UTF_8));reset=false;}
     for(var s:rows){out.write(("id: "+s.sessionId()+":"+s.sequence()+"\nevent: snapshot\ndata: "+json.writeValueAsString(new Latest(s,Math.max(0,java.time.Duration.between(s.timestamp(),java.time.Instant.now()).toMillis()),store.status()))+"\n\n").getBytes(StandardCharsets.UTF_8));seq=s.sequence();}
    }else out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
    out.write(("event: experiment\ndata: "+json.writeValueAsString(experiments.latest())+"\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
   }
  };
  return ResponseEntity.ok().header("X-Accel-Buffering","no").body(body);
 }
 @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
 public ResponseEntity<Void> saturated(){return ResponseEntity.status(503).build();}
 private static long TimeUnitSeconds(long s){return s*1_000_000_000L;}
}
