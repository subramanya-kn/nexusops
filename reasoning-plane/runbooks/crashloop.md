# Crash loop

Symptom: high restartCount growing quickly, container repeatedly exits non-zero shortly
after start. Health never becomes ready.
Cause: startup failure — bad config/env var, failed dependency, or a broken image from a
recent deploy.
Remediation: check get_recent_deploys. If a deploy immediately precedes the crash loop,
ROLLBACK_IMAGE to the last known-good digest. If it correlates with a config/env change,
escalate for a config fix (NO_OP + escalate). RESTART_CONTAINER alone will not fix a
deterministic startup crash.
