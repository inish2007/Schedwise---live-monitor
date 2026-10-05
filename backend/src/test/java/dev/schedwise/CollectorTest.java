package dev.schedwise;
import dev.schedwise.linux.*;
import dev.schedwise.monitor.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class CollectorTest {
 static class Source implements ProcSource {
  long time=1_000_000_000L,start=10,cpu=0;boolean present=true,denied=false,statusDenied=false;int n=0;
  public long nanoTime(){return time;}
  public List<Long> pids(){return present?List.of(42L):List.of();}
  public String read(String p)throws IOException {
   if(p.equals("/proc/stat"))return "cpu "+n+" 0 0 "+(n+100)+" 0 0 0 0";
   if(p.equals("/proc/42/stat")&&!denied)return ProcParserTest.stat(42,"test ) process",start,cpu);
   if(p.equals("/proc/42/status")&&!statusDenied)return "Uid:\t1000 1000 1000 1000\nCpus_allowed_list:\t0-1";
   throw new IOException("unavailable");
  }
  void advance(){time+=1_000_000_000L;n+=10;cpu+=20;}
 }
 Capabilities caps(){return new Capabilities(new LinuxSource(){@Override public String read(String p){return "test-boot";}@Override public String command(String... args){return "100";}});}
 @Test void identityExitAndInaccessibleStates(){Source s=new Source();Collector c=new Collector(s,caps());c.collect();assertEquals("FIRST_SAMPLE",c.latest().processes().getFirst().cpuPercent().reason());assertNull(c.latest().cpuPressureSome().value());s.advance();c.collect();assertEquals(20,c.latest().processes().getFirst().cpuPercent().value());s.start=11;s.advance();c.collect();assertEquals("IDENTITY_CHANGED",c.latest().processes().getFirst().cpuPercent().reason());s.present=false;s.advance();c.collect();assertEquals("EXITED",c.latest().processes().getFirst().lifecycle());s.present=true;s.advance();c.collect();s.denied=true;s.advance();c.collect();assertEquals(1,c.latest().unreadableProcesses());assertEquals("INACCESSIBLE_OR_IDENTITY_CHANGED",c.latest().processes().getFirst().lifecycle());}
 @Test void unavailableStatusDoesNotBecomeZero(){Source s=new Source();s.statusDenied=true;Collector c=new Collector(s,caps());c.collect();assertNull(c.latest().processes().getFirst().uid().value());assertNull(c.latest().processes().getFirst().allowedCpus().value());}
 @Test void ringBoundAndIndependentReads(){Source s=new Source();Collector c=new Collector(s,caps());for(int i=0;i<130;i++){c.collect();s.advance();}assertEquals(120,c.after(0).size());assertEquals(130,c.latest().sequence());assertEquals(130,c.latest().sequence());assertEquals(1,c.after(129).size());}
}
