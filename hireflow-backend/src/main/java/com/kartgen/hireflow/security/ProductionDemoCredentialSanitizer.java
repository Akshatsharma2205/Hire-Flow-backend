package com.kartgen.hireflow.security;

import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Ensures that known demo credentials seeded by initial database migrations
 * cannot be used in a production environment.
 *
 * Runs automatically upon application startup under the 'prod' profile.
 * Neutralizes seeded accounts idempotently without violating foreign-key constraints
 * or breaking migration history.
 */
@Slf4j
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProductionDemoCredentialSanitizer implements ApplicationRunner {

    public static final UUID DEMO_ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID DEMO_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final String DEMO_EMAIL = "hr@kartgen.com";
    public static final String DEMO_TOKEN_STRING = "hf_ext_live_org_kartgen_01_demo12345";
    public static final String DEMO_TOKEN_HASH = "1cb0422aaebbb2b818e16172d1d29427125aea0b6c49cebebc06f53413370fc2";

    private final UserRepository userRepo;
    private final OrganizationUserRepository orgUserRepo;
    private final OrganizationRepository orgRepo;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ProductionDemoCredentialSanitizer(UserRepository userRepo,
                                             OrganizationUserRepository orgUserRepo,
                                             OrganizationRepository orgRepo,
                                             PlatformTransactionManager transactionManager) {
        this.userRepo = userRepo;
        this.orgUserRepo = orgUserRepo;
        this.orgRepo = orgRepo;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public ProductionDemoCredentialSanitizer(UserRepository userRepo,
                                             OrganizationUserRepository orgUserRepo,
                                             OrganizationRepository orgRepo) {
        this(userRepo, orgUserRepo, orgRepo, (PlatformTransactionManager) null);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        sanitize();
    }

    @Transactional
    public void sanitize() {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> executeSanitization());
        } else {
            executeSanitization();
        }
    }

    protected void executeSanitization() {
        log.info("Production security: Scanning for seeded demo credentials in database...");

        // 1. Neutralize demo user password hash so 'password123' cannot match
        userRepo.findByEmail(DEMO_EMAIL).ifPresent(user -> {
            String currentHash = user.getPasswordHash();
            if (currentHash != null && !currentHash.startsWith("DISABLED_")) {
                user.setPasswordHash("DISABLED_DEMO_HASH_" + UUID.randomUUID());
                userRepo.save(user);
                log.warn("Production security: Neutralized password hash for seeded demo user '{}'", DEMO_EMAIL);
            }
        });

        // 2. Clear demo extension token hash and disable membership
        orgUserRepo.findByOrganizationIdAndUserId(DEMO_ORG_ID, DEMO_USER_ID).ifPresent(ou -> {
            boolean changed = false;
            if (ou.getExtensionTokenHash() != null) {
                ou.setExtensionTokenHash(null);
                changed = true;
            }
            if ("ACTIVE".equals(ou.getStatus())) {
                ou.setStatus("DISABLED");
                changed = true;
            }
            if (changed) {
                orgUserRepo.save(ou);
                log.warn("Production security: Disabled membership and cleared extension token for demo org={}", DEMO_ORG_ID);
            }
        });

        // 3. Clear any other record holding the known demo extension token hash
        orgUserRepo.findByExtensionTokenHash(DEMO_TOKEN_HASH).ifPresent(ou -> {
            ou.setExtensionTokenHash(null);
            ou.setStatus("DISABLED");
            orgUserRepo.save(ou);
            log.warn("Production security: Cleared known demo extension token hash from org_user id={}", ou.getId());
        });

        // 4. Suspend demo organization
        orgRepo.findById(DEMO_ORG_ID).ifPresent(org -> {
            if ("ACTIVE".equals(org.getStatus())) {
                org.setStatus("SUSPENDED");
                orgRepo.save(org);
                log.warn("Production security: Suspended seeded demo organization id={}", DEMO_ORG_ID);
            }
        });

        log.info("Production security: Seeded demo credential check completed.");
    }
}

