# Dependency failure and slow-dependency cascade

Symptom: service unhealthy but its own metrics are normal; a downstream dependency is down
or slow. Logs show timeouts calling another service. Latency climbs across the call graph.
Cause: a dependency (database, cache, upstream service) is unavailable or degraded.
Remediation: consult get_dependency_graph to find the failing dependency, then act on THAT
service (RESTART_CONTAINER of the dependency, or CLEAR_CACHE if a cache is thrashing).
Restarting the symptomatic service does not fix a broken dependency — target the root.
