package com.kartgen.hireflow.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;

/** Creates genuine, parseable PDF resumes for end-to-end pipeline tests. */
public final class TestResumePdf {

    private TestResumePdf() {}

    public static byte[] create(String resumeText) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 10);
                content.setLeading(14f);
                content.newLineAtOffset(50, 790);
                for (String line : resumeText.split("\n")) {
                    content.showText(sanitize(line));
                    content.newLine();
                }
                content.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build test PDF", e);
        }
    }

    public static String resume(String name, String email, String city,
                                int yearsExperience, String documentDate) {
        return """
                CURRICULUM VITAE

                Name: %s
                Email: %s
                Phone: +91-9000000001
                Location: %s, India

                PROFESSIONAL SUMMARY
                Backend engineer with %d years of hands-on experience building
                production services with Java, Spring Boot and PostgreSQL.

                WORK EXPERIENCE
                Senior Engineer - Test Labs, January 2021 - Present
                - Built REST APIs and microservices using Java and Spring Boot
                - Deployed services with Docker and Kafka

                TECHNICAL SKILLS
                Java, Spring Boot, PostgreSQL, Docker, Kafka, REST APIs

                EDUCATION
                B.Tech Computer Science - Pune University, 2020

                TOTAL EXPERIENCE: %d years
                Last updated: %s
                """.formatted(name, email, city, yearsExperience, yearsExperience, documentDate);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^\\x20-\\x7E\\t]", " ");
    }
}
