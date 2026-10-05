package dev.schedwise;
import dev.schedwise.api.SessionAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthTest {
 @TempDir Path dir;
 static MockHttpServletRequest request(String method,String route){var req=new MockHttpServletRequest(method,route);req.addHeader("Host","localhost:8080");return req;}

 @Test void allApiRoutesRequireTokenAndMutationsFailClosed()throws Exception{
  System.setProperty("schedwise.data",dir.toString());
  try{
   SessionAuth auth=new SessionAuth();String token=Files.readString(dir.resolve("session-token"));
   for(String route:new String[]{"/api/gameshield/status","/api/gameshield/candidates","/api/events","/api/capabilities","/api/snapshots/latest","/api/sessions/id/export"}){
    MockHttpServletRequest req=request("GET",route);MockHttpServletResponse res=new MockHttpServletResponse();auth.doFilter(req,res,new MockFilterChain());assertEquals(401,res.getStatus());
   }
   MockHttpServletRequest ok=request("GET","/api/events");ok.addHeader("Authorization","Bearer "+token);MockHttpServletResponse res=new MockHttpServletResponse();MockFilterChain chain=new MockFilterChain();auth.doFilter(ok,res,chain);assertNotNull(chain.getRequest());
   MockHttpServletRequest evil=request("GET","/api/events");evil.addHeader("Authorization","Bearer "+token);evil.addHeader("Origin","https://untrusted.example");res=new MockHttpServletResponse();auth.doFilter(evil,res,new MockFilterChain());assertEquals(403,res.getStatus());
   MockHttpServletRequest post=request("POST","/api/actions/nice");post.addHeader("Authorization","Bearer "+token);res=new MockHttpServletResponse();auth.doFilter(post,res,new MockFilterChain());assertEquals(403,res.getStatus());
   for(String route:new String[]{"/api/gameshield/activate","/api/gameshield/deactivate"}){
    for(String method:new String[]{"POST","DELETE"}){
     MockHttpServletRequest request=request(method,route);request.addHeader("Authorization","Bearer "+token);
     MockHttpServletResponse denied=new MockHttpServletResponse();auth.doFilter(request,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
     request=request(method,route);request.addHeader("Authorization","Bearer "+token);request.addHeader("Origin","http://127.0.0.1:5173");
     MockHttpServletResponse response=new MockHttpServletResponse();MockFilterChain allowed=new MockFilterChain();auth.doFilter(request,response,allowed);
     if(method.equals("POST"))assertNotNull(allowed.getRequest());else assertEquals(405,response.getStatus());
    }
   }
   assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("session-token"))));
  }finally{System.clearProperty("schedwise.data");}
 }
 @Test void browserCookieBootstrapCsrfRotationAndHostGuards()throws Exception{
  System.setProperty("schedwise.data",dir.toString());
  try{
   SessionAuth auth=new SessionAuth();String launch=Files.readString(dir.resolve("launch-bootstrap"));
   var req=request("POST","/api/session/bootstrap");req.addHeader("Origin","http://localhost:8080");req.addHeader("X-Launch-Bootstrap",launch);
   var res=new MockHttpServletResponse();auth.doFilter(req,res,new MockFilterChain());assertEquals(200,res.getStatus());
   String cookie=res.getHeader("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
   String id=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
   String csrf=new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getContentAsString()).get("csrfToken").asText();
   var replay=request("POST","/api/session/bootstrap");replay.addHeader("Origin","http://localhost:8080");replay.addHeader("X-Launch-Bootstrap",launch);
   res=new MockHttpServletResponse();auth.doFilter(replay,res,new MockFilterChain());assertEquals(401,res.getStatus());
   for(String route:new String[]{"/api/gameshield/heartbeat","/api/recommendations/00000000-0000-0000-0000-000000000001/reject"}) for(boolean valid:new boolean[]{false,true}){
    var action=request("POST",route);action.addHeader("Origin","http://localhost:8080");action.setCookies(new jakarta.servlet.http.Cookie("schedwise_session",id));if(valid)action.addHeader("X-CSRF-Token",csrf);
    res=new MockHttpServletResponse();var chain=new MockFilterChain();auth.doFilter(action,res,chain);
    if(valid)assertNotNull(chain.getRequest());else assertEquals(403,res.getStatus());
   }
   var foreign=request("GET","/api/session");foreign.removeHeader("Host");foreign.addHeader("Host","evil.example:8080");foreign.setCookies(new jakarta.servlet.http.Cookie("schedwise_session",id));
   res=new MockHttpServletResponse();auth.doFilter(foreign,res,new MockFilterChain());assertEquals(403,res.getStatus());
   var previous=request("GET","/api/session");previous.setCookies(new jakarta.servlet.http.Cookie("schedwise_session",id));res=new MockHttpServletResponse();new SessionAuth().doFilter(previous,res,new MockFilterChain());assertEquals(401,res.getStatus());
  }finally{System.clearProperty("schedwise.data");}
 }

}
