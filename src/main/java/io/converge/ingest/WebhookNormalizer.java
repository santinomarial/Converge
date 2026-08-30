package io.converge.ingest;

import java.util.UUID;

public interface WebhookNormalizer {

    String sourceSystem();

    void normalize(UUID rawWebhookId);
}

