package io.nexusops.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchTest {

    @Test
    void startsDisengaged() {
        assertThat(new KillSwitch().isEngaged()).isFalse();
    }

    @Test
    void engageAndRelease() {
        KillSwitch killSwitch = new KillSwitch();
        killSwitch.engage("manual test");
        assertThat(killSwitch.isEngaged()).isTrue();
        killSwitch.release("manual test");
        assertThat(killSwitch.isEngaged()).isFalse();
    }
}
