package com.kartgen.hireflow.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start without an explicit, known Spring profile.
 *
 * <p>This closes the previous implicit-local-startup path: omitting
 * {@code SPRING_PROFILES_ACTIVE} can no longer fall back to development
 * defaults. The guard also refuses a mixed {@code prod} + {@code local}
 * activation, which would otherwise load the local webhook credential
 * seeder into a production context.</p>
 *
 * <p>Registered through {@code META-INF/spring.factories} so it runs before
 * the application context refreshes.</p>
 */
public class StartupProfileGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Set<String> KNOWN_PROFILES = Set.of("local", "test", "prod");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        List<String> active = Arrays.asList(applicationContext.getEnvironment().getActiveProfiles());
        if (active.isEmpty()) {
            throw new IllegalStateException(
                    "SPRING_PROFILES_ACTIVE must be set explicitly to local, test or prod; "
                            + "refusing to start without an active profile");
        }
        if (!KNOWN_PROFILES.containsAll(active)) {
            throw new IllegalStateException(
                    "SPRING_PROFILES_ACTIVE contains an unsupported profile; allowed values are local, test or prod; got "
                            + active);
        }
        if (active.contains("prod") && active.contains("local")) {
            throw new IllegalStateException(
                    "SPRING_PROFILES_ACTIVE must not combine the prod and local profiles");
        }
    }
}
