package io.converge.ingest;

import java.io.Reader;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.converge.connectors.WarehouseFeedParser;
import io.converge.identity.IdentityResolution;
import io.converge.identity.IdentityService;
import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;

@Service
public class WarehouseFeedIngestor {

    private final WarehouseFeedParser parser;
    private final IdentityService identity;
    private final LedgerService ledger;

    public WarehouseFeedIngestor(WarehouseFeedParser parser, IdentityService identity, LedgerService ledger) {
        this.parser = parser;
        this.identity = identity;
        this.ledger = ledger;
    }

    public FeedResult ingest(String feedId, Reader csv) {
        int accepted = 0;
        int quarantined = 0;
        int rowNumber = 1;
        for (var row : parser.parse(csv)) {
            rowNumber++;
            Map<String, Object> payload = Map.of("feedId", feedId, "row", rowNumber);
            IdentityResolution resolution = identity.resolve(
                    "warehouse", row.externalSkuId(), row.externalLocationId(), payload);
            if (resolution instanceof IdentityResolution.Quarantined) {
                quarantined++;
                continue;
            }
            var canonical = ((IdentityResolution.Mapped) resolution).identity();
            ledger.append(new AppendInventoryEvent(UUID.randomUUID(), canonical.canonicalSkuId(), canonical.locationId(),
                    "warehouse", feedId + ":" + rowNumber, InventoryEventType.COUNT, EventKind.SNAPSHOT,
                    null, row.qty(), row.occurredAt(), null, payload));
            accepted++;
        }
        return new FeedResult(accepted, quarantined);
    }

    public record FeedResult(int accepted, int quarantined) { }
}

