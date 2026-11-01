package com.kartgen.hireflow.extraction;

import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;
import com.kartgen.hireflow.profile.Skill;
import com.kartgen.hireflow.profile.SkillAliasRepository;
import com.kartgen.hireflow.profile.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSmartHeuristicExtractorTest {

    private LocalSmartHeuristicExtractor extractor;

    private static final String SAMPLE = """
            CURRICULUM VITAE

            Rahul Sharma
            Email: rahul.sharma@example.com | Phone: +91-9876543210
            Location: Bangalore, India
            LinkedIn: https://linkedin.com/in/rahul-sharma

            PROFESSIONAL SUMMARY
            Senior Java Spring Boot Developer with 7 years of hands-on experience
            building production systems with Java, Spring Boot and PostgreSQL.

            WORK EXPERIENCE

            Senior Developer - Tech Solutions Pvt Ltd, Bangalore
            January 2019 - Present
            - Designed REST APIs and microservices using Java and Spring Boot

            TECHNICAL SKILLS
            Java, Spring Boot, PostgreSQL, Docker, Kubernetes, Kafka

            EDUCATION
            B.Tech Computer Science - IIT Delhi, 2017

            Last updated: Jan 2025
            """;

    @BeforeEach
    void setUp() {
        SkillRepository skillRepo = mock(SkillRepository.class);
        SkillAliasRepository aliasRepo = mock(SkillAliasRepository.class);

        when(skillRepo.findByActiveTrue()).thenReturn(List.of(
                skill("Java"),
                skill("Spring Boot"),
                skill("PostgreSQL"),
                skill("Docker"),
                skill("Kubernetes"),
                skill("Kafka")
        ));
        when(aliasRepo.findAll()).thenReturn(List.of());

        extractor = new LocalSmartHeuristicExtractor(skillRepo, aliasRepo);
        extractor.refreshCatalog();
    }

    @Test
    void extractsIdentitySkillsExperienceAndMetadata() {
        ExtractedResumeSchema schema = extractor.extract(SAMPLE, "rahul.pdf", null);

        assertThat(schema.getFullName()).isEqualTo("Rahul Sharma");
        assertThat(schema.getEmails()).containsExactly("rahul.sharma@example.com");
        assertThat(schema.getPhones()).containsExactly("9876543210");
        assertThat(schema.getLinkedinUrl()).contains("linkedin.com/in/rahul-sharma");
        assertThat(schema.getExtractedSkills())
                .contains("Java", "Spring Boot", "PostgreSQL", "Docker", "Kubernetes", "Kafka");
        assertThat(schema.getTotalExperienceMonths()).isEqualTo(84);
        assertThat(schema.getExperienceBasis()).isEqualTo("explicit");
        assertThat(schema.getCurrentCity()).isEqualTo("Bangalore");
        assertThat(schema.getDocumentDate()).isEqualTo("2025-01-01");
        assertThat(schema.getEducationList()).isNotEmpty();
        assertThat(schema.getEducationList().get(0).getDegree()).containsIgnoringCase("B.Tech");
        assertThat(schema.getSourceEvidenceJson()).containsKeys("emails", "phones", "skills");
    }

    @Test
    void derivesExperienceFromOverlappingDateRanges() {
        String text = """
                Priya Patel
                priya.patel@example.com

                WORK EXPERIENCE
                Senior Engineer - Acme, Mar 2020 - Present
                Engineer - Beta Corp, Jun 2017 - Feb 2020
                """;

        ExtractedResumeSchema schema = extractor.extract(text, "priya.pdf", null);

        assertThat(schema.getExperienceBasis()).isEqualTo("date_ranges");
        assertThat(schema.getTotalExperienceMonths()).isGreaterThan(24);
    }

    @Test
    void ignoresHrSenderAddressForIdentityButKeepsResumeEmail() {
        ExtractedResumeSchema schema = extractor.extract(
                "Amit Verma\namit.verma@example.com\nJava, Docker\n",
                "amit.pdf",
                "hr@headhunters.example");

        assertThat(schema.getFullName()).isEqualTo("Amit Verma");
        assertThat(schema.getEmails()).containsExactly("amit.verma@example.com");
    }

    @Test
    void normalizesIndianAndInternationalPhoneNumbers() {
        assertThat(LocalSmartHeuristicExtractor.normalizePhone("+91 98765 43210"))
                .isEqualTo("9876543210");
        assertThat(LocalSmartHeuristicExtractor.normalizePhone("09876543210"))
                .isEqualTo("9876543210");
        assertThat(LocalSmartHeuristicExtractor.normalizePhone("+1 (415) 555-2671"))
                .isEqualTo("+14155552671");
        assertThat(LocalSmartHeuristicExtractor.normalizePhone("12345")).isNull();
    }

    @Test
    void returnsEmptySchemaForBlankInput() {
        ExtractedResumeSchema schema = extractor.extract("   ", "empty.pdf", null);
        assertThat(schema.getEmails()).isNull();
        assertThat(schema.getExtractedSkills()).isNull();
    }

    private Skill skill(String name) {
        Skill skill = new Skill();
        skill.setId(UUID.randomUUID());
        skill.setCanonicalName(name);
        skill.setCategory("Technology");
        skill.setActive(true);
        return skill;
    }
}
