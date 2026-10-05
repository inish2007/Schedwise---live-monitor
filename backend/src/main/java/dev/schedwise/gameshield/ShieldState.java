package dev.schedwise.gameshield;
import dev.schedwise.model.Telemetry.Identity;
import java.util.List;
public record ShieldState(boolean active, String status, String operationId, Long targetPid, String targetName,
 String strategy, Integer pinnedCore, List<ShieldedProcess> affectedProcesses, long activatedAtEpochMs,
 long expiresAtEpochMs, Double estimatedCpuFreed, Long estimatedRamFreedBytes,
 List<Failure> failures, List<Event> events, boolean guardianAvailable) {
 public record Failure(String code,String message,Identity identity,boolean retryable) {}
 public record Event(String code,String message,long timestampMs) {}
 public static ShieldState idle(){return new ShieldState(false,"OFF",null,null,null,null,null,List.of(),0,0,null,null,List.of(),List.of(),false);}
 public static ShieldState unavailable(String message){return new ShieldState(true,"NEEDS_ATTENTION",null,null,null,null,null,List.of(),0,0,null,null,List.of(new Failure("GUARDIAN_UNAVAILABLE",message,null,true)),List.of(),false);}
}
