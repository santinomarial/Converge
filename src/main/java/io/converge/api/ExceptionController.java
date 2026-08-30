package io.converge.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.converge.exceptions.ExceptionQueueService;
import io.converge.exceptions.ExceptionQueueService.ResolutionAction;
import io.converge.exceptions.ReconciliationException;

@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {
    private final ExceptionQueueService exceptions;
    public ExceptionController(ExceptionQueueService exceptions) { this.exceptions = exceptions; }

    @GetMapping
    public List<ReconciliationException> find(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String severity) {
        return exceptions.find(state, severity);
    }

    @PostMapping("/{id}/claim")
    public ReconciliationException claim(@PathVariable UUID id, @RequestBody ActorRequest request) {
        return exceptions.claim(id, request.actor());
    }

    @PostMapping("/{id}/resolve")
    public ReconciliationException resolve(@PathVariable UUID id, @RequestBody ResolveRequest request) {
        return exceptions.resolve(id, request.action(), request.qty(), request.note(), request.actor());
    }

    public record ActorRequest(String actor) { }
    public record ResolveRequest(ResolutionAction action, Integer qty, String note, String actor) { }
}

