package dev.schedwise.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Component
public class SessionAuth extends OncePerRequestFilter {
 private final String token=random(), bootstrap=random();
 private final long bootstrapUntil=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
 private boolean bootstrapUsed=false;
 private record Session(String csrf,long until){}
 private final Map<String,Session> sessions=new LinkedHashMap<>();
 private final ObjectMapper json=new ObjectMapper();
 private static final Set<String> ORIGINS=Set.of("http://127.0.0.1:5173","http://localhost:5173","http://127.0.0.1:8080","http://localhost:8080");
 private static final Set<String> HOSTS=Set.of("127.0.0.1:5173","localhost:5173","127.0.0.1:8080","localhost:8080");
 public SessionAuth() throws IOException {
  Path dir=Path.of(System.getProperty("schedwise.data","../data"));Files.createDirectories(dir);
  if(Files.isSymbolicLink(dir))throw new IOException("Unsafe data directory");
  Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));
  writeSecret(dir.resolve("session-token"),token);
  writeSecret(dir.resolve("launch-bootstrap"),bootstrap);
 }
 private static void writeSecret(Path path,String value)throws IOException{
  if(Files.isSymbolicLink(path))throw new IOException("Credential path must not be a symlink");
  if(!Files.exists(path))Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
  Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));Files.writeString(path,value,StandardOpenOption.TRUNCATE_EXISTING);
 }
 private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 private static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
 private synchronized Session cookie(HttpServletRequest req){
  sessions.entrySet().removeIf(e->System.nanoTime()>e.getValue().until());
  if(req.getCookies()!=null)for(Cookie c:req.getCookies())if(c.getName().equals("schedwise_session"))return sessions.get(c.getValue());
  return null;
 }
 private void reply(HttpServletResponse res,int status,Object body)throws IOException{res.setStatus(status);res.setContentType("application/json");json.writeValue(res.getOutputStream(),body);}
 private void deny(HttpServletResponse res,int status,String code)throws IOException{reply(res,status,Map.of("code",code,"error",code));}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","no-referrer");
  if(!HOSTS.contains(req.getHeader("Host"))){deny(res,403,"HOST_REJECTED");return;}
  String path=req.getRequestURI();if(!path.startsWith("/api/")){chain.doFilter(req,res);return;}
  String origin=req.getHeader("Origin");
  if(origin!=null&&!ORIGINS.contains(origin)){deny(res,403,"ORIGIN_REJECTED");return;}
  if("cross-site".equals(req.getHeader("Sec-Fetch-Site"))){deny(res,403,"CROSS_SITE_REJECTED");return;}
  if(origin!=null){res.setHeader("Access-Control-Allow-Origin",origin);res.setHeader("Vary","Origin");res.setHeader("Access-Control-Allow-Credentials","true");res.setHeader("Access-Control-Allow-Headers","Authorization, Content-Type, Last-Event-ID, X-CSRF-Token, X-Launch-Bootstrap");res.setHeader("Access-Control-Allow-Methods","GET, POST, OPTIONS");}
  if(req.getMethod().equals("OPTIONS")){res.setStatus(204);return;}
  boolean bearer=equal(req.getHeader("Authorization"),"Bearer "+token);
  Session session=cookie(req);
  if(path.equals("/api/session/bootstrap")&&req.getMethod().equals("POST")){
   if(origin==null){deny(res,403,"ORIGIN_REQUIRED");return;}
   synchronized(this){
    boolean launch=!bootstrapUsed&&System.nanoTime()<bootstrapUntil&&equal(req.getHeader("X-Launch-Bootstrap"),bootstrap);
    if(!bearer&&!launch&&session==null){deny(res,401,"OPEN_LOCAL_LAUNCHER");return;}
    if(launch)bootstrapUsed=true;
    if(session==null){String id=random();session=new Session(random(),System.nanoTime()+java.util.concurrent.TimeUnit.HOURS.toNanos(8));
     if(sessions.size()>=32)sessions.remove(sessions.keySet().iterator().next());sessions.put(id,session);
     res.addHeader("Set-Cookie","schedwise_session="+id+"; Path=/api; HttpOnly; SameSite=Strict"+(req.isSecure()?"; Secure":""));
    }
   }
   reply(res,200,Map.of("csrfToken",session.csrf()));return;
  }
  if(!bearer&&session==null){deny(res,401,"SESSION_EXPIRED");return;}
  if(path.equals("/api/session")&&req.getMethod().equals("GET")){
   if(session==null){deny(res,401,"BROWSER_SESSION_REQUIRED");return;}reply(res,200,Map.of("csrfToken",session.csrf()));return;
  }
  if(!req.getMethod().equals("GET")){
   if(origin==null){deny(res,403,"ORIGIN_REQUIRED");return;}
   if(!bearer&&!equal(req.getHeader("X-CSRF-Token"),session.csrf())){deny(res,403,"CSRF_REJECTED");return;}
   boolean allowed=req.getMethod().equals("POST")&&(Set.of("/api/experiments","/api/simulations","/api/recommendations","/api/actions/nice","/api/roles/tags","/api/gameshield/activate","/api/gameshield/deactivate","/api/gameshield/heartbeat").contains(path)||path.matches("/api/recommendations/[a-f0-9-]{36}/reject")||path.matches("/api/experiments/[a-f0-9-]{36}/(stop|reset)"));
   if(!allowed){deny(res,405,"METHOD_REJECTED");return;}
   if(req.getContentLengthLong()>16384){deny(res,413,"REQUEST_TOO_LARGE");return;}
  }
  if(req.getMethod().equals("POST")){
   byte[] body=req.getInputStream().readNBytes(16385);
   if(body.length>16384){deny(res,413,"REQUEST_TOO_LARGE");return;}
   HttpServletRequest wrapped=new HttpServletRequestWrapper(req){
    @Override public ServletInputStream getInputStream(){
     var buffer=new java.io.ByteArrayInputStream(body);
     return new ServletInputStream(){public int read(){return buffer.read();}public boolean isFinished(){return buffer.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};
    }
    @Override public java.io.BufferedReader getReader(){return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
   };
   chain.doFilter(wrapped,res);return;
  }
  chain.doFilter(req,res);
 }
}
