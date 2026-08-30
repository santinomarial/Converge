package io.converge.identity;

import java.util.UUID;

public sealed interface IdentityResolution {

    record Mapped(CanonicalIdentity identity) implements IdentityResolution {
    }

    record Quarantined(UUID quarantineId, String reason) implements IdentityResolution {
    }
}

