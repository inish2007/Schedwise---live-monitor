package dev.schedwise.linux;
import java.util.*;
public final class ProcParser {
 private ProcParser() {}
 public record Stat(long pid,String name,String state,long ticks,int nice,int threads,long startTicks) {}
 public static Stat stat(String line) {
  int left=line.indexOf('('),right=line.lastIndexOf(')');
  if(left<1 || right<=left) throw new IllegalArgumentException("Invalid stat command field");
  String[] f=line.substring(right+1).strip().split("\\s+");
  if(f.length<20) throw new IllegalArgumentException("Truncated stat");
  return new Stat(Long.parseLong(line.substring(0,left).strip()),line.substring(left+1,right),f[0],
    Math.addExact(Long.parseLong(f[11]),Long.parseLong(f[12])),Integer.parseInt(f[16]),Integer.parseInt(f[17]),Long.parseLong(f[19]));
 }
 public static Map<String,String> status(String text) {
  Map<String,String> m=new HashMap<>();
  text.lines().forEach(l->{int i=l.indexOf(':');if(i>0)m.put(l.substring(0,i),l.substring(i+1).strip());});return m;
 }
 public static Map<String,List<Long>> cpus(String text) {
  Map<String,List<Long>> m=new LinkedHashMap<>();
  text.lines().filter(l->l.matches("cpu[0-9]*\\s+.*" )).limit(4097).forEach(l->{
   String[] f=l.strip().split("\\s+");if(f.length<9)throw new IllegalArgumentException("Truncated CPU counters");
   List<Long> c=new ArrayList<>();for(int i=1;i<=8;i++)c.add(Long.parseLong(f[i]));m.put(f[0],List.copyOf(c));
  }); if(m.isEmpty())throw new IllegalArgumentException("No CPU counters");return m;
 }
 public static double[] cpuDelta(List<Long> before,List<Long> after) {
  if(before==null)throw new IllegalArgumentException("FIRST_SAMPLE");
  long total=0;long[] d=new long[8];
  for(int i=0;i<8;i++){if(before.get(i)<0||after.get(i)<0)throw new IllegalArgumentException("INVALID_COUNTER");d[i]=Math.subtractExact(after.get(i),before.get(i));if(d[i]<0)throw new IllegalArgumentException("COUNTER_RESET");total=Math.addExact(total,d[i]);}
  if(total<=0)throw new IllegalArgumentException("INVALID_INTERVAL");
  return new double[]{Math.clamp(100.0*(total-d[3]-d[4]-d[7])/total,0.0,100.0),100.0*d[7]/total};
 }
 public static double processPercent(long before,long after,long hz,double seconds) {
  if(hz<=0 || seconds<=0 || !Double.isFinite(seconds))throw new IllegalArgumentException("INVALID_INTERVAL_OR_CLOCK");
  if(before<0||after<0)throw new IllegalArgumentException("INVALID_COUNTER");
  if(after<before)throw new IllegalArgumentException("COUNTER_RESET");
  return 100.0*(after-before)/hz/seconds;
 }
}
