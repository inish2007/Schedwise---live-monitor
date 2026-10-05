package dev.schedwise;
import dev.schedwise.linux.ProcParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProcParserTest {
 static String stat(long pid,String name,long start,long cpu){
  String[] f=new String[20];Arrays.fill(f,"0");f[0]="R";f[11]=""+cpu;f[12]="7";f[16]="5";f[17]="2";f[19]=""+start;
  return pid+" ("+name+") "+String.join(" ",f);
 }
 @Test void namesAndFieldOffsets(){var p=ProcParser.stat(stat(123,"a (b) c))",999,31));assertEquals("a (b) c))",p.name());assertEquals(38,p.ticks());assertEquals(999,p.startTicks());assertEquals(5,p.nice());assertEquals(2,p.threads());}
 @Test void invalidStat(){assertThrows(IllegalArgumentException.class,()->ProcParser.stat("2 bad"));assertThrows(IllegalArgumentException.class,()->ProcParser.stat("2 (bad) R 2"));}
 @Test void percentAllowsMultipleCoresAndZero(){assertEquals(200,ProcParser.processPercent(0,400,100,2));assertEquals(0,ProcParser.processPercent(4,4,250,1));assertEquals(50,ProcParser.processPercent(0,250,250,2));}
 @Test void invalidDeltas(){assertThrows(IllegalArgumentException.class,()->ProcParser.processPercent(5,4,100,1));assertThrows(IllegalArgumentException.class,()->ProcParser.processPercent(0,1,0,1));assertThrows(IllegalArgumentException.class,()->ProcParser.processPercent(0,1,100,0));}
 @Test void guestNotDoubleCountedAndStealSeparate(){var before=ProcParser.cpus("cpu  100 0 0 100 0 0 0 0 90 0").get("cpu");var after=ProcParser.cpus("cpu\t120 0 0 150 10 0 0 20 110 0").get("cpu");var p=ProcParser.cpuDelta(before,after);assertEquals(20,p[0]);assertEquals(20,p[1]);assertEquals(8,after.size());}
 @Test void cpuResetAndNoInterval(){List<Long> zero=Collections.nCopies(8,0L);assertThrows(IllegalArgumentException.class,()->ProcParser.cpuDelta(null,zero));assertThrows(IllegalArgumentException.class,()->ProcParser.cpuDelta(zero,zero));assertThrows(IllegalArgumentException.class,()->ProcParser.cpuDelta(List.of(1L,0L,0L,0L,0L,0L,0L,0L),zero));}
 @Test void overflowAndNegativeCountersNeverBecomeValidPercentages(){
  List<Long> zero=Collections.nCopies(8,0L);
  assertThrows(IllegalArgumentException.class,()->ProcParser.cpuDelta(List.of(-1L,0L,0L,0L,0L,0L,0L,0L),zero));
  assertThrows(ArithmeticException.class,()->ProcParser.cpuDelta(zero,List.of(Long.MAX_VALUE,1L,0L,0L,0L,0L,0L,0L)));
  assertThrows(IllegalArgumentException.class,()->ProcParser.processPercent(-1,1,100,1));
 }
}
