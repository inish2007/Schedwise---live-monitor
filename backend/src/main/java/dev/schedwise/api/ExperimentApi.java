package dev.schedwise.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.schedwise.experiment.ExperimentManager;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ExperimentApi {
    private final ExperimentManager experiments;
    private final dev.schedwise.persistence.SessionStore sessionStore;
    public record StartRequest(Integer core) {}
    public ExperimentApi(ExperimentManager experiments, dev.schedwise.persistence.SessionStore sessionStore) {
        this.experiments = experiments;
        this.sessionStore = sessionStore;
    }
    @PostMapping("/experiments")
    public ResponseEntity<JsonNode> start(@RequestBody StartRequest request) throws Exception {
        return ResponseEntity.status(202).body(experiments.start(request.core()));
    }
    @GetMapping("/experiments") public JsonNode latest() { return experiments.latest(); }
    @GetMapping("/experiments/{id}") public JsonNode get(@PathVariable String id) { return experiments.get(id); }
    @GetMapping("/experiments/{id}/comparison") public JsonNode comparison(@PathVariable String id) { return experiments.getComparison(id); }
    @PostMapping("/experiments/{id}/stop") public JsonNode stop(@PathVariable String id) { return experiments.stop(id); }
    @GetMapping("/sessions") public List<dev.schedwise.persistence.SessionStore.SessionInfo> sessions() { return sessionStore.listSessions(); }
    @GetMapping("/sessions/{id}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String id) {
        var directory = experiments.exportDirectory(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"schedwise-"+directory.getFileName()+".zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(output -> experiments.writeExport(directory, output));
    }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<Map<String,String>> missing(Exception e) { return error(404,e); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(Exception e) { return error(400,e); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> conflict(Exception e) { return error(409,e); }
    private ResponseEntity<Map<String,String>> error(int code, Exception e) { return ResponseEntity.status(code).body(Map.of("error",e.getMessage())); }
}
