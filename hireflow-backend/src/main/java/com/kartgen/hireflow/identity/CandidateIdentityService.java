package com.kartgen.hireflow.identity;

import com.kartgen.hireflow.extraction.LocalSmartHeuristicExtractor;
import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateIdentityService {

    private final CandidateRepository candidateRepo;
    private final CandidateIdentifierRepository identifierRepo;
    private final IdentityReviewCaseRepository reviewCaseRepo;

    /** Sources where the message sender is definitely the candidate (direct applicant). */
    private static final Set<String> CANDIDATE_SENDER_SOURCES = Set.of(
            "DIRECT"
    );

    @Transactional
    public Candidate resolveOrCreate(UUID orgId, ExtractedResumeSchema schema,
                                     String senderEmail, String sourceType) {
        List<String> emails = new ArrayList<>();
        if (sourceType != null && CANDIDATE_SENDER_SOURCES.contains(sourceType.toUpperCase(Locale.ROOT))
                && senderEmail != null && !senderEmail.isBlank()) {
            String normalizedSender = normalizeEmail(senderEmail);
            if (normalizedSender != null) emails.add(normalizedSender);
        }
        if (schema.getEmails() != null) {
            schema.getEmails().stream().map(CandidateIdentityService::normalizeEmail)
                    .filter(Objects::nonNull).forEach(emails::add);
        }
        emails = emails.stream().distinct().toList();

        List<String> phones = schema.getPhones() == null ? List.of() : schema.getPhones().stream()
                .map(LocalSmartHeuristicExtractor::normalizePhone)
                .filter(Objects::nonNull).distinct().toList();

        String linkedin = normalizeLinkedin(schema.getLinkedinUrl());

        List<Candidate> emailMatches = findCandidates(orgId, "EMAIL", emails);
        List<Candidate> phoneMatches = findCandidates(orgId, "PHONE", phones);
        List<Candidate> linkedinMatches = linkedin != null
                ? findCandidates(orgId, "LINKEDIN", List.of(linkedin)) : List.of();

        LinkedHashSet<Candidate> allMatches = new LinkedHashSet<>();
        allMatches.addAll(emailMatches);
        allMatches.addAll(phoneMatches);
        allMatches.addAll(linkedinMatches);

        if (allMatches.size() > 1) {
            Candidate primary = emailMatches.isEmpty() ? allMatches.iterator().next() : emailMatches.get(0);
            Candidate other = allMatches.stream().filter(c -> !c.getId().equals(primary.getId())).findFirst().orElse(null);
            reviewCaseRepo.save(IdentityReviewCase.builder()
                    .organizationId(orgId)
                    .candidateId(primary.getId())
                    .conflictingCandidateId(other != null ? other.getId() : null)
                    .reasonCode("MERGE_CONFLICT")
                    .details("Resume identifiers matched multiple candidate records")
                    .build());
        }

        Candidate existing = emailMatches.stream().findFirst()
                .or(() -> phoneMatches.stream().findFirst())
                .or(() -> linkedinMatches.stream().findFirst())
                .orElse(null);

        if (existing != null) {
            String extractedName = schema.getFullName();
            boolean conflict = extractedName != null && existing.getDisplayName() != null
                    && !existing.getDisplayName().isBlank()
                    && !namesCompatible(existing.getDisplayName(), extractedName);

            if (conflict) {
                log.warn("Name conflict for org={}: existing='{}', extracted='{}'",
                        orgId, existing.getDisplayName(), extractedName);
                Candidate created = createCandidate(orgId, schema, emails, phones, linkedin, sourceType, true);
                reviewCaseRepo.save(IdentityReviewCase.builder()
                        .organizationId(orgId)
                        .candidateId(created.getId())
                        .conflictingCandidateId(existing.getId())
                        .reasonCode("CONFLICT")
                        .details("Existing name: " + existing.getDisplayName()
                                + " vs extracted: " + extractedName
                                + ". A separate candidate was created pending HR review.")
                        .build());
                return created;
            }

            updateIdentifierTimestamps(existing.getId(), emails, phones, linkedin);
            log.debug("Resolved to existing candidate id={}", existing.getId());
            return existing;
        }

        return createCandidate(orgId, schema, emails, phones, linkedin, sourceType, false);
    }

    private Candidate createCandidate(UUID orgId, ExtractedResumeSchema schema, List<String> emails,
                                      List<String> phones, String linkedin, String sourceType,
                                      boolean conflict) {
        Candidate candidate = Candidate.builder()
                .organizationId(orgId)
                .displayName(schema.getFullName())
                .primaryEmail(emails.isEmpty() ? null : emails.get(0))
                .primaryPhone(phones.isEmpty() ? null : phones.get(0))
                .linkedinUrl(linkedin)
                .currentExperienceMonths(schema.getTotalExperienceMonths())
                .experienceConfidence(schema.getTotalExperienceMonths() != null ? "MEDIUM" : "UNKNOWN")
                .currentCity(schema.getCurrentCity())
                .status("ACTIVE")
                .build();
        candidateRepo.save(candidate);

        emails.forEach(v -> saveIdentifier(orgId, candidate.getId(), "EMAIL", v, sourceType, conflict));
        phones.forEach(v -> saveIdentifier(orgId, candidate.getId(), "PHONE", v, sourceType, conflict));
        if (linkedin != null) {
            saveIdentifier(orgId, candidate.getId(), "LINKEDIN", linkedin, sourceType, conflict);
        }

        log.info("Created candidate id={} org={} conflict={}", candidate.getId(), orgId, conflict);
        return candidate;
    }

    private List<Candidate> findCandidates(UUID orgId, String type, List<String> values) {
        LinkedHashMap<UUID, Candidate> found = new LinkedHashMap<>();
        for (String value : values) {
            for (CandidateIdentifier identifier : identifierRepo
                    .findByOrganizationIdAndIdentifierTypeAndNormalizedValue(orgId, type, value)) {
                candidateRepo.findById(identifier.getCandidateId()).ifPresent(c -> found.putIfAbsent(c.getId(), c));
            }
        }
        return new ArrayList<>(found.values());
    }

    private void saveIdentifier(UUID orgId, UUID candidateId, String type, String value,
                                String sourceType, boolean conflict) {
        if (value == null || value.isBlank()) return;
        boolean exists = identifierRepo
                .findByOrganizationIdAndIdentifierTypeAndNormalizedValue(orgId, type, value)
                .stream().anyMatch(i -> i.getCandidateId().equals(candidateId));
        if (!exists) {
            identifierRepo.save(CandidateIdentifier.builder()
                    .organizationId(orgId)
                    .candidateId(candidateId)
                    .identifierType(type)
                    .normalizedValue(value)
                    .sourceType(sourceType != null ? sourceType : "RESUME")
                    .verificationStatus(conflict ? "CONFLICT" : "UNVERIFIED")
                    .build());
        }
    }

    private void updateIdentifierTimestamps(UUID candidateId, List<String> emails,
                                            List<String> phones, String linkedin) {
        Set<String> values = new HashSet<>(emails);
        values.addAll(phones);
        if (linkedin != null) values.add(linkedin);
        Instant now = Instant.now();
        identifierRepo.findByCandidateId(candidateId).stream()
                .filter(i -> values.contains(i.getNormalizedValue()))
                .forEach(i -> {
                    i.setLastSeenAt(now);
                    identifierRepo.save(i);
                });
    }

    public static String normalizeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeLinkedin(String linkedin) {
        if (linkedin == null || linkedin.isBlank()) return null;
        String value = linkedin.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "");
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.isBlank() ? null : value;
    }

    static boolean namesCompatible(String a, String b) {
        if (a == null || b == null) return true;
        String normA = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        String normB = b.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        if (normA.equals(normB)) return true;
        if (normA.isBlank() || normB.isBlank()) return true;

        List<String> wordsA = new ArrayList<>(Arrays.asList(normA.split(" ")));
        List<String> wordsB = new ArrayList<>(Arrays.asList(normB.split(" ")));
        if (wordsA.size() == 1 || wordsB.size() == 1) {
            return wordsA.containsAll(wordsB) || wordsB.containsAll(wordsA);
        }

        // Two shared name tokens or matching first and last names
        String firstA = wordsA.get(0);
        String firstB = wordsB.get(0);
        String lastA = wordsA.get(wordsA.size() - 1);
        String lastB = wordsB.get(wordsB.size() - 1);
        if (firstA.equals(firstB) && lastA.equals(lastB)) return true;

        Set<String> shared = new HashSet<>(wordsA);
        shared.retainAll(wordsB);
        shared.removeIf(w -> w.length() <= 1);
        return shared.size() >= 2;
    }
}
