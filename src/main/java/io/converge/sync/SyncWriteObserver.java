package io.converge.sync;

import java.util.UUID;

/** A fault-injection/telemetry seam invoked after a remote write and before local acknowledgement. */
@FunctionalInterface
public interface SyncWriteObserver {
    void afterExternalWrite(UUID attemptId);
}
