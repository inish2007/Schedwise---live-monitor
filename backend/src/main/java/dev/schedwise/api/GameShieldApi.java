package dev.schedwise.api;
import dev.schedwise.gameshield.*;
import dev.schedwise.model.Telemetry.Identity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/gameshield")
public class GameShieldApi {
 private final ShieldController controller;
 public GameShieldApi(ShieldController controller){this.controller=controller;}
 public record ActivateRequest(Identity targetIdentity,List<ShieldController.Selection> targets,Integer maxSeconds,String operationId,String ownerId){}
 public record StopRequest(String sessionId,String operationId){}
 public record HeartbeatRequest(String sessionId,String ownerId){}
 public record Error(String code,String message,String error,Identity identity,boolean retryable,ShieldState recovery){}
 @GetMapping("/status") public ShieldState status(){return controller.getState();}
 @GetMapping("/candidates") public List<ShieldCandidate> candidates(){return controller.scanCandidates();}
 @PostMapping("/activate") public ShieldState activate(@RequestBody ActivateRequest req){return controller.activate(req.targetIdentity(),req.targets(),req.maxSeconds()==null?900:req.maxSeconds(),req.operationId(),req.ownerId());}
 @PostMapping("/deactivate") public ShieldState stop(@RequestBody StopRequest req){return controller.deactivate(req.sessionId(),req.operationId());}
 @PostMapping("/heartbeat") public ShieldState heartbeat(@RequestBody HeartbeatRequest req){return controller.heartbeat(req.sessionId(),req.ownerId());}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<Error> error(RuntimeException e){
  boolean conflict=e instanceof IllegalStateException;String code=conflict?"SHIELD_CONFLICT":"SELECTION_INVALID";
  String message=e.getMessage()==null?"Shield request failed":e.getMessage();
  String lower=message.toLowerCase();
  if(lower.contains("guardian"))code="GUARDIAN_UNAVAILABLE";
  else if(lower.contains("permission")||lower.contains("not permitted"))code="PERMISSION_DENIED";
  else if(lower.contains("timed out"))code="ACTION_TIMEOUT";
  else if(lower.contains("identity")||lower.contains("selection changed"))code="STALE_SELECTION";
  else if(lower.contains("lease"))code="LEASE_OWNER_MISMATCH";

  return ResponseEntity.status(conflict?409:400).body(new Error(code,message,message,null,conflict,controller.getState()));
 }
}
