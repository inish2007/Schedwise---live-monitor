package dev.schedwise.api;

import dev.schedwise.simulation.CaptureService;
import dev.schedwise.simulation.SimulationModels.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class SimulationApi {
    private final CaptureService captureService;
    private final dev.schedwise.experiment.ExperimentManager experimentManager;

    public SimulationApi(CaptureService captureService, dev.schedwise.experiment.ExperimentManager experimentManager) {
        this.captureService = captureService;
        this.experimentManager = experimentManager;
    }

    @GetMapping("/captures")
    public List<CaptureInfo> listCaptures() {
        return captureService.listCaptures();
    }

    @GetMapping("/captures/{id}/comparison")
    public com.fasterxml.jackson.databind.JsonNode getComparison(@PathVariable String id) {
        return experimentManager.getComparison(id);
    }

    @GetMapping("/captures/{id}")
    public CaptureInfo getCapture(@PathVariable String id) {
        return captureService.getCapture(id);
    }

    @PostMapping("/simulations")
    public ResponseEntity<SimulationResponse> simulate(@RequestBody SimulationRequest request) throws IOException {
        return ResponseEntity.ok(captureService.simulate(request));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
    }
}
