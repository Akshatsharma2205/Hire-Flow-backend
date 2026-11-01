package com.kartgen.hireflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupProfileGuardTest {

    @Test
    void refusesToStartWithoutAnActiveProfile() {
        assertRejected();
    }

    @Test
    void rejectsUnknownProfile() {
        assertRejected("staging");
    }

    @Test
    void rejectsMixedLocalAndProductionProfiles() {
        assertRejected("local", "prod");
    }

    @Test
    void rejectsKnownProfileMixedWithUnknownProfile() {
        assertRejected("test", "staging");
    }

    @Test
    void acceptsExplicitLocalTestAndProductionProfiles() {
        assertThatCode(() -> initialize("local")).doesNotThrowAnyException();
        assertThatCode(() -> initialize("test")).doesNotThrowAnyException();
        assertThatCode(() -> initialize("prod")).doesNotThrowAnyException();
    }

    @Test
    void isRegisteredThroughSpringFactories() {
        List<String> names = SpringFactoriesLoader.loadFactoryNames(
                ApplicationContextInitializer.class, getClass().getClassLoader());

        assertThat(names).contains(StartupProfileGuard.class.getName());
    }

    private static void assertRejected(String... profiles) {
        assertThatThrownBy(() -> initialize(profiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    private static void initialize(String... profiles) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            new StartupProfileGuard().initialize(context);
        }
    }
}
