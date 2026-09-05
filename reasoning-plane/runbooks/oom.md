# Out-of-memory (OOM) kills

Symptom: container exits with code 137, `oomKilled=true`, memPct near 100 before exit.
Cause: process working set exceeds the container memory limit, often under load.
Remediation: RESTART_CONTAINER to recover the service immediately. If the leak recurs,
raise the memory limit or SCALE_SERVICE to spread load. Do NOT ROLLBACK_IMAGE unless a
recent deploy correlates with the onset — OOM is usually load, not a bad image.
