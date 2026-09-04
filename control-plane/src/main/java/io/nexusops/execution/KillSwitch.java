package io.nexusops.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global execution kill switch. When engaged, the executor refuses every capability
 * invocation (fail-closed). Toggled by an ADMIN via the execution controller.
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);
    private final AtomicBoolean engaged = new AtomicBoolean(false);

    public boolean isEngaged() {
        return engaged.get();
    }

    public void engage(String reason) {
        engaged.set(true);
        log.warn("KILL SWITCH ENGAGED: {}", reason);
    }

    public void release(String reason) {
        engaged.set(false);
        log.warn("Kill switch released: {}", reason);
    }
}
