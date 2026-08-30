package io.converge.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.AppendResult;
import io.converge.ledger.InventoryEvent;
import io.converge.ledger.InventoryPosition;
import io.converge.ledger.LedgerService;

@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public AppendResult appendSyntheticEvent(@RequestBody AppendInventoryEvent event) {
        return ledger.append(event);
    }

    @GetMapping("/positions")
    public List<InventoryPosition> positions(
            @RequestParam(required = false) Long sku,
            @RequestParam(required = false) Long location) {
        return ledger.findPositions(sku, location);
    }

    @GetMapping("/positions/{sku}/{location}")
    public InventoryPosition position(@PathVariable long sku, @PathVariable long location) {
        return ledger.getPosition(sku, location)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/positions/{sku}/{location}/history")
    public List<InventoryEvent> history(@PathVariable long sku, @PathVariable long location) {
        return ledger.history(sku, location);
    }

    @PostMapping("/admin/replay")
    public ReplayResponse replay() {
        return new ReplayResponse(ledger.replay());
    }

    public record ReplayResponse(int aggregatesRebuilt) {
    }
}

