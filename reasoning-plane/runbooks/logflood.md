# Log flood and disk pressure

Symptom: log volume spikes; disk usage climbs; metrics otherwise nominal. Container may be
emitting repeated errors or debug spam.
Cause: a stuck loop, misconfigured log level, or noisy dependency retries.
Remediation: ROTATE_LOG to relieve disk pressure. If the flood is driven by a crash/retry
loop, address that root cause (RESTART_CONTAINER or dependency fix). A healthy system with
normal logs needs NO_OP — do not act on noise.
