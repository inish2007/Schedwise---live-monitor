package dev.schedwise;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.simulation.CaptureAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class CaptureIntegrityTest {
 @TempDir Path dir;
 private CaptureAdapter.CapturedWorkload load(String summary,String event)throws Exception {
  Files.writeString(dir.resolve("summary.json"),summary);Files.writeString(dir.resolve("events.jsonl"),event);
  return new CaptureAdapter(new ObjectMapper()).loadFromExperiment(dir,"CONTENTION",5);
 }
 private static final String SUMMARY="{\"phases\":[{\"name\":\"CONTENTION\",\"startNs\":100,\"endNs\":1000}]}";
 private static final String EVENT="{\"event\":\"service_request\",\"phase\":\"CONTENTION\",\"requestId\":\"test\",\"arrivalNs\":110,\"cpuServiceNs\":10}";
 @Test void missingWorkersCannotCreateSyntheticCohort()throws Exception {var result=load(SUMMARY,EVENT);assertEquals("INSUFFICIENT",result.sufficiency());assertTrue(result.jobs().isEmpty());}
 @Test void missingServiceDemandCannotUseDefault38ms()throws Exception {var result=load(SUMMARY,EVENT.replace("\"cpuServiceNs\":10","\"cpuServiceNs\":0"));assertEquals("INSUFFICIENT",result.sufficiency());assertTrue(result.jobs().isEmpty());}
 @Test void corruptSummaryIsInsufficient()throws Exception {assertEquals("INSUFFICIENT",load("{bad",EVENT).sufficiency());}
 @Test void duplicateRequestIdsAreRejected()throws Exception {assertEquals("INSUFFICIENT",load(SUMMARY,EVENT+"\n"+EVENT).sufficiency());}
}
