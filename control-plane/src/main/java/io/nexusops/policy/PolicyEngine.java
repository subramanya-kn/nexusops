package io.nexusops.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Declarative, order-sensitive policy engine (policy-as-data, see ADR-004).
 *
 * <p>Rules are evaluated top to bottom. Every rule whose {@code when} matches "fires"
 * and contributes its decision; the strongest contribution wins with precedence
 * {@code DENY > REQUIRE_HUMAN > AUTO_APPROVE} (fail-safe). All fired rule ids are
 * returned for explainability. If nothing fires, the configured default applies.
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final ResourceLoader resourceLoader;
    private final RiskScorer riskScorer;
    private final String rulesLocation;
    private volatile PolicyRuleSet ruleSet;

    public PolicyEngine(ResourceLoader resourceLoader,
                        RiskScorer riskScorer,
                        @Value("${nexusops.policy.rules-location:classpath:policy-rules.yaml}")
                        String rulesLocation) {
        this.resourceLoader = resourceLoader;
        this.riskScorer = riskScorer;
        this.rulesLocation = rulesLocation;
    }

    @PostConstruct
    public void load() {
        Resource resource = resourceLoader.getResource(rulesLocation);
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = resource.getInputStream()) {
            this.ruleSet = yaml.readValue(in, PolicyRuleSet.class);
            log.info("Loaded {} policy rules from {}", ruleSet.rules().size(), rulesLocation);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load policy rules from " + rulesLocation, e);
        }
    }

    /** Reload rules at runtime (ADMIN only, via controller). */
    public void reload() {
        load();
    }

    public PolicyResult evaluate(PolicyContext ctx) {
        double risk = riskScorer.score(ctx.plan(), ctx.environment());
        List<String> fired = new ArrayList<>();
        PolicyDecision winner = null;

        for (PolicyRule rule : ruleSet.rules()) {
            if (rule.when() != null && rule.when().matches(ctx)) {
                fired.add(rule.id());
                winner = strongest(winner, rule.decision());
            }
        }

        if (winner == null) {
            winner = ruleSet.defaultDecision();
            return new PolicyResult(winner, List.of(), risk,
                    "no rule matched; applied default " + winner);
        }
        return new PolicyResult(winner, fired, risk,
                "decision " + winner + " from rules " + fired);
    }

    private static PolicyDecision strongest(PolicyDecision current, PolicyDecision candidate) {
        if (current == null) {
            return candidate;
        }
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(PolicyDecision d) {
        return switch (d) {
            case AUTO_APPROVE -> 0;
            case REQUIRE_HUMAN -> 1;
            case DENY -> 2;
        };
    }
}
