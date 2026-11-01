package com.kartgen.hireflow.extraction;

import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;
import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema.EducationEntry;
import com.kartgen.hireflow.profile.Skill;
import com.kartgen.hireflow.profile.SkillAlias;
import com.kartgen.hireflow.profile.SkillAliasRepository;
import com.kartgen.hireflow.profile.SkillRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local heuristic resume extractor. Zero API cost, deterministic and fast.
 * Uses compiled regex, section hints and the skill/alias catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hireflow.ai.provider", havingValue = "local", matchIfMissing = true)
public class LocalSmartHeuristicExtractor implements ResumeExtractionPort {

    private final SkillRepository skillRepo;
    private final SkillAliasRepository aliasRepo;

    private record CompiledAlias(String canonical, Pattern pattern, int aliasLength) {}

    /** Pre-compiled thread-safe alias patterns. */
    private volatile List<CompiledAlias> compiledAliases = List.of();

    private static final Pattern EMAIL_RE =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_RE =
            Pattern.compile("(?:\\+?\\d{1,3}[\\s.\\-]?)?(?:\\(?\\d{2,5}\\)?[\\s.\\-]?)?\\d{3,5}[\\s.\\-]?\\d{3,5}");
    private static final Pattern LINKEDIN_RE =
            Pattern.compile("(?:https?://)?(?:www\\.)?linkedin\\.com/in/[\\w\\-]+/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_RE =
            Pattern.compile("(?:https?://)?(?:www\\.)?github\\.com/[\\w\\-]+/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_YEARS_RE = Pattern.compile(
            "(\\d{1,2}(?:\\.\\d)?)\\+?\\s*(?:years?|yrs?)(?:\\s+(?:of|in))?(?:\\s+[\\w&/+\\-]+){0,4}\\s+(?:experience|exp)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_PLAIN_RE = Pattern.compile(
            "\\b(?:experience|exp)\\s*[:\\-]?\\s*(\\d{1,2}(?:\\.\\d)?)\\+?\\s*(?:years?|yrs?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_RANGE_RE = Pattern.compile(
            "((?:(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s*)?(?:19|20)\\d{2}" +
                    "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s*(?:19|20)\\d{2}" +
                    "|\\d{1,2}/\\d{4})" +
                    "\\s*(?:-|–|—|to|until|till)\\s*" +
                    "((?:(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s*)?(?:19|20)\\d{2}" +
                    "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s*(?:19|20)\\d{2}" +
                    "|\\d{1,2}/\\d{4}|[Pp]resent|[Cc]urrent|[Tt]ill\\s+[Dd]ate|[Nn]ow)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDU_DEGREE_RE = Pattern.compile(
            "\\b(B\\.?\\s?Tech|M\\.?\\s?Tech|B\\.?E\\.?|M\\.?E\\.?|B\\.?Sc|M\\.?Sc|B\\.?C\\.?A|M\\.?C\\.?A|" +
                    "B\\.?Com|M\\.?Com|BBA|MBA|Ph\\.?D|Bachelor(?:'s)?|Master(?:'s)?|Diploma|PUC|HSC|SSLC)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_LABEL_RE = Pattern.compile(
            "(?:location|current location|based in|city)\\s*[:\\-]\\s*([A-Za-z .'\\-]{2,50})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOC_DATE_RE = Pattern.compile(
            "(?:last\\s+updated|updated\\s+on|as\\s+of|document\\s+date)\\s*[:\\-]?\\s*" +
                    "((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s*(?:19|20)\\d{2}" +
                    "|(?:19|20)\\d{2}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/(?:19|20)\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> MAJOR_CITIES = Set.of(
            "bangalore", "bengaluru", "mumbai", "delhi", "new delhi", "hyderabad", "chennai", "pune",
            "kolkata", "ahmedabad", "jaipur", "lucknow", "noida", "gurugram", "gurgaon", "chandigarh",
            "indore", "coimbatore", "kochi", "thiruvananthapuram", "nagpur", "vizag", "visakhapatnam",
            "bhopal", "patna", "mangalore", "mysore", "remote", "san francisco", "new york", "london",
            "singapore", "dubai", "toronto", "berlin", "sydney", "tokyo");

    private static final Set<String> HR_LOCAL_PARTS = Set.of(
            "hr", "recruit", "recruiter", "careers", "jobs", "hiring", "noreply", "no-reply", "talent");

    private static final Set<String> NON_NAME_LINE_WORDS = Set.of(
            "curriculum", "vitae", "resume", "profile", "summary", "objective", "contact", "email",
            "phone", "mobile", "linkedin", "github", "address", "location", "experience", "education",
            "skills", "projects", "certifications", "languages", "interests", "declaration", "about",
            "professional", "work", "history", "references", "portfolio", "website", "career",
            "highlights", "overview", "executive", "technical", "personal", "details", "qualifications",
            "activities", "achievements", "employment", "background", "synopsis");

    private static final Set<String> EDUCATION_CONTEXT_WORDS = Set.of(
            "b.tech", "m.tech", "b.e", "m.e", "b.sc", "m.sc", "bca", "mca",
            "bachelor", "master", "university", "college", "school", "degree",
            "diploma", "cgpa", "gpa", "percentage", "hsc", "sslc", "cbse", "icse");

    private static final Set<String> TITLE_WORDS = Set.of(
            "engineer", "developer", "manager", "analyst", "consultant", "architect", "designer",
            "specialist", "lead", "director", "administrator", "scientist", "intern", "associate");

    @PostConstruct
    public void loadSkillCatalog() {
        refreshCatalog();
    }

    /** Reloads the catalog; useful after skills are edited at runtime. */
    public synchronized void refreshCatalog() {
        Map<String, String> next = new HashMap<>();
        List<Skill> skills = skillRepo.findByActiveTrue();
        for (Skill s : skills) {
            next.put(normalizeAlias(s.getCanonicalName()), s.getCanonicalName());
        }
        Map<UUID, String> namesById = new HashMap<>();
        for (Skill s : skills) namesById.put(s.getId(), s.getCanonicalName());
        for (SkillAlias a : aliasRepo.findAll()) {
            String canonical = namesById.get(a.getSkillId());
            if (canonical != null) {
                next.put(normalizeAlias(a.getAliasNormalized()), canonical);
            }
        }

        List<CompiledAlias> compiled = new ArrayList<>();
        for (Map.Entry<String, String> entry : next.entrySet()) {
            String alias = entry.getKey();
            if (alias.isBlank()) continue;
            Pattern p = Pattern.compile("(?<![a-z0-9+#])" + Pattern.quote(alias) + "(?![a-z0-9+#])");
            compiled.add(new CompiledAlias(entry.getValue(), p, alias.length()));
        }
        // Longest aliases first so "spring boot" wins over "spring".
        compiled.sort((a, b) -> Integer.compare(b.aliasLength(), a.aliasLength()));
        this.compiledAliases = Collections.unmodifiableList(compiled);
        log.info("Loaded {} skill aliases into extraction lookup", compiled.size());
    }

    private String normalizeAlias(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Override
    public ExtractedResumeSchema extract(String rawText, String fileName, String senderEmailHint) {
        if (rawText == null || rawText.isBlank()) {
            return ExtractedResumeSchema.builder().build();
        }
        long start = System.nanoTime();
        String text = rawText.replace('\u0000', ' ').replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> evidence = new LinkedHashMap<>();

        List<String> emails = extractEmails(text, senderEmailHint);
        if (!emails.isEmpty()) evidence.put("emails", String.join(", ", emails));

        List<String> phones = extractPhones(text);
        if (!phones.isEmpty()) evidence.put("phones", String.join(", ", phones));

        String linkedin = extractFirst(LINKEDIN_RE, text);
        if (linkedin != null) evidence.put("linkedin", linkedin);

        String github = extractFirst(GITHUB_RE, text);
        if (github != null) evidence.put("github", github);

        String fullName = extractFullName(text, emails, senderEmailHint);
        if (fullName != null) evidence.put("fullName", fullName);

        List<String> skills = extractSkills(text);
        if (!skills.isEmpty()) {
            evidence.put("skills", String.join(", ", skills));
            String snippet = firstLineContainingAny(text, skills);
            if (snippet != null) evidence.put("skillsEvidence", snippet);
        }

        ExperienceResult exp = extractExperience(text);
        if (exp.months != null) {
            evidence.put("experienceMonths", exp.months + " months");
            if (exp.evidence != null) evidence.put("experienceEvidence", exp.evidence);
        }

        String city = extractCity(text);
        if (city != null) evidence.put("city", city);

        String documentDate = extractDocumentDate(text);
        if (documentDate != null) evidence.put("documentDate", documentDate);

        List<EducationEntry> education = extractEducation(text);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.debug("Heuristic extraction completed in {}ms for {}", elapsed, fileName);

        return ExtractedResumeSchema.builder()
                .fullName(fullName)
                .emails(emails)
                .phones(phones)
                .linkedinUrl(linkedin)
                .githubUrl(github)
                .extractedSkills(skills)
                .totalExperienceMonths(exp.months)
                .experienceBasis(exp.basis)
                .currentCity(city)
                .educationList(education)
                .documentDate(documentDate)
                .sourceEvidenceJson(evidence)
                .build();
    }

    private List<String> extractEmails(String text, String senderHint) {
        Matcher m = EMAIL_RE.matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) {
            String email = m.group().toLowerCase(Locale.ROOT);
            if (isHrAddress(email)) continue;
            if (senderHint != null && email.equalsIgnoreCase(senderHint.trim())) {
                // A direct sender is still a valid candidate identity; keep it.
                found.add(email);
                continue;
            }
            found.add(email);
        }
        return new ArrayList<>(found);
    }

    private boolean isHrAddress(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return HR_LOCAL_PARTS.contains(local);
    }

    private List<String> extractPhones(String text) {
        Matcher m = PHONE_RE.matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) {
            String normalized = normalizePhone(m.group());
            if (normalized != null) found.add(normalized);
        }
        return new ArrayList<>(found);
    }

    /** Normalizes Indian and international numbers to a stable identifier form. */
    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^\\d]", "");
        if (digits.length() < 10 || digits.length() > 15) return null;
        if (digits.length() == 12 && digits.startsWith("91")) return digits.substring(2);
        if (digits.length() == 11 && digits.startsWith("0")) return digits.substring(1);
        if (digits.length() == 10) return digits;
        return "+" + digits;
    }

    private String extractFullName(String text, List<String> emails, String senderHint) {
        // 1. Explicit "Name:" label.
        Matcher label = Pattern.compile("(?im)^\\s*(?:full\\s+name|name)\\s*[:\\-]\\s*([A-Za-z][A-Za-z.'\\-]*(?:\\s+[A-Za-z][A-Za-z.'\\-]*){0,3})\\s*$")
                .matcher(text);
        if (label.find()) {
            String candidate = cleanName(label.group(1));
            if (candidate != null) return candidate;
        }

        // 2. Header heuristic: scan the first 15 non-empty lines.
        String[] lines = text.split("\\n");
        int scanned = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (++scanned > 15) break;
            String candidate = cleanName(trimmed);
            if (candidate != null && !looksLikeTitle(candidate)) return candidate;
        }

        // 3. Derive from email.
        if (!emails.isEmpty()) {
            return nameFromEmail(emails.get(0));
        }
        if (senderHint != null && senderHint.contains("@")) {
            return nameFromEmail(senderHint);
        }
        return null;
    }

    private String cleanName(String line) {
        if (line == null) return null;
        String candidate = line.trim().replaceAll("\\s+", " ");
        if (candidate.length() < 3 || candidate.length() > 60) return null;
        if (candidate.contains("@") || candidate.contains("http") || candidate.contains("|")) return null;
        if (candidate.matches(".*\\d.*")) return null;
        if (candidate.endsWith(":")) return null;

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (NON_NAME_LINE_WORDS.stream().anyMatch(lower::contains)) return null;

        String[] words = candidate.split("\\s+");
        if (words.length < 2 || words.length > 4) return null;
        for (String word : words) {
            if (!word.matches("[A-Za-z][A-Za-z.'\\-]*")) return null;
        }

        boolean startsUpper = Arrays.stream(words)
                .allMatch(w -> Character.isUpperCase(w.charAt(0)));
        boolean allCaps = candidate.equals(candidate.toUpperCase(Locale.ROOT));
        if (!startsUpper && !allCaps) return null;

        if (allCaps) {
            return Arrays.stream(words)
                    .map(w -> w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT))
                    .reduce((a, b) -> a + " " + b).orElse(candidate);
        }
        return candidate;
    }

    private boolean looksLikeTitle(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return TITLE_WORDS.stream().anyMatch(lower::contains);
    }

    private String nameFromEmail(String email) {
        String local = email.split("@")[0];
        String cleaned = local.replaceAll("[._%+\\-]+", " ").replaceAll("\\d+", "").trim();
        if (cleaned.isBlank()) return null;
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(w -> w.length() > 1)
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + " " + b).orElse(null);
    }

    private List<String> extractSkills(String text) {
        String normalizedText = " " + text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.\\s]", " ")
                .replaceAll("\\s+", " ") + " ";
        Set<String> matched = new LinkedHashSet<>();

        for (CompiledAlias ca : compiledAliases) {
            if (ca.pattern().matcher(normalizedText).find()) {
                matched.add(ca.canonical());
            }
        }
        return new ArrayList<>(matched);
    }

    private record ExperienceResult(Integer months, String basis, String evidence) {}

    private ExperienceResult extractExperience(String text) {
        // Prefer an explicit claim such as "7 years of hands-on experience".
        Matcher explicit = EXP_YEARS_RE.matcher(text);
        if (explicit.find()) {
            double years = Double.parseDouble(explicit.group(1));
            if (years > 0 && years <= 50) {
                return new ExperienceResult((int) Math.round(years * 12), "explicit",
                        lineAround(text, explicit.start(), explicit.end()));
            }
        }
        Matcher explicit2 = EXP_PLAIN_RE.matcher(text);
        if (explicit2.find()) {
            double years = Double.parseDouble(explicit2.group(1));
            if (years > 0 && years <= 50) {
                return new ExperienceResult((int) Math.round(years * 12), "explicit",
                        lineAround(text, explicit2.start(), explicit2.end()));
            }
        }

        // Otherwise union overlapping employment intervals to avoid double counting.
        List<YearMonth[]> intervals = new ArrayList<>();
        Matcher dr = DATE_RANGE_RE.matcher(text);
        String evidence = null;
        while (dr.find()) {
            String surrounding = lineAround(text, dr.start(), dr.end()).toLowerCase(Locale.ROOT);
            // Skip academic/education date intervals
            if (EDUCATION_CONTEXT_WORDS.stream().anyMatch(surrounding::contains)) {
                continue;
            }

            YearMonth start = parseYearMonth(dr.group(1));
            YearMonth end = isPresent(dr.group(2)) ? YearMonth.now() : parseYearMonth(dr.group(2));
            if (start == null || end == null || end.isBefore(start)) continue;
            if (start.getYear() < 1970) continue;
            intervals.add(new YearMonth[]{start, end});
            if (evidence == null) evidence = lineAround(text, dr.start(), dr.end());
        }

        if (intervals.isEmpty()) {
            return new ExperienceResult(null, "unknown", null);
        }

        intervals.sort(Comparator.comparing(a -> a[0]));
        YearMonth curStart = intervals.get(0)[0];
        YearMonth curEnd = intervals.get(0)[1];
        long totalMonths = 0;
        for (int i = 1; i < intervals.size(); i++) {
            YearMonth s = intervals.get(i)[0];
            YearMonth e = intervals.get(i)[1];
            if (!s.isAfter(curEnd.plusMonths(1))) {
                if (e.isAfter(curEnd)) curEnd = e;
            } else {
                totalMonths += java.time.temporal.ChronoUnit.MONTHS.between(curStart, curEnd) + 1;
                curStart = s;
                curEnd = e;
            }
        }
        totalMonths += java.time.temporal.ChronoUnit.MONTHS.between(curStart, curEnd) + 1;

        if (totalMonths <= 0 || totalMonths > 600) {
            return new ExperienceResult(null, "date_ranges", evidence);
        }
        return new ExperienceResult((int) totalMonths, "date_ranges", evidence);
    }

    private boolean isPresent(String value) {
        return value.toLowerCase(Locale.ROOT).matches("present|current|now|till\\s+date");
    }

    private YearMonth parseYearMonth(String value) {
        String s = value.trim().replace(".", "");
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("yyyy", Locale.ENGLISH));
        for (DateTimeFormatter f : formats) {
            try {
                if (f == formats.get(3)) {
                    return YearMonth.of(Integer.parseInt(s), 1);
                }
                return YearMonth.parse(s, f);
            } catch (DateTimeParseException | NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String extractCity(String text) {
        Matcher label = LOCATION_LABEL_RE.matcher(text);
        if (label.find()) {
            String candidate = label.group(1).trim();
            String city = matchKnownCity(candidate);
            if (city != null) return city;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String city : MAJOR_CITIES) {
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(city) + "(?![a-z])").matcher(lower).find()) {
                return titleCase(city);
            }
        }
        return null;
    }

    private String matchKnownCity(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String city : MAJOR_CITIES) {
            if (lower.contains(city)) return titleCase(city);
        }
        return null;
    }

    private String titleCase(String city) {
        return Arrays.stream(city.split(" "))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .reduce((a, b) -> a + " " + b).orElse(city);
    }

    private List<EducationEntry> extractEducation(String text) {
        List<EducationEntry> results = new ArrayList<>();
        String[] lines = text.split("\\n");
        for (String line : lines) {
            Matcher degreeMatcher = EDU_DEGREE_RE.matcher(line);
            if (!degreeMatcher.find()) continue;
            String degree = degreeMatcher.group(1);
            Matcher yearM = Pattern.compile("((?:19|20)\\d{2})").matcher(line);
            String year = yearM.find() ? yearM.group(1) : null;
            String rest = line.substring(degreeMatcher.end()).trim();
            rest = rest.replaceAll("^[,\\-–—:\\s]+", "").trim();
            String institution = rest.isEmpty() ? null : rest.split("[,;\\d]")[0].trim();
            if (institution != null && institution.length() > 120) institution = institution.substring(0, 120);
            results.add(EducationEntry.builder()
                    .degree(degree).institution(institution).year(year).build());
        }
        return results;
    }

    private String extractDocumentDate(String text) {
        Matcher m = DOC_DATE_RE.matcher(text);
        if (!m.find()) return null;
        String raw = m.group(1).trim();
        // Normalize "Jan 2024" to 2024-01-01; ISO and d/m/y are normalized too.
        try {
            Matcher iso = Pattern.compile("((?:19|20)\\d{2})-(\\d{2})-(\\d{2})").matcher(raw);
            if (iso.matches()) return iso.group(1) + "-" + iso.group(2) + "-" + iso.group(3);
            Matcher dmy = Pattern.compile("(\\d{1,2})/(\\d{1,2})/((?:19|20)\\d{2})").matcher(raw);
            if (dmy.matches()) {
                return dmy.group(3) + "-" + String.format("%02d", Integer.parseInt(dmy.group(2)))
                        + "-" + String.format("%02d", Integer.parseInt(dmy.group(1)));
            }
            YearMonth ym = parseYearMonth(raw);
            if (ym != null) return ym + "-01";
        } catch (Exception ignored) {
        }
        return null;
    }

    private String lineAround(String text, int start, int end) {
        int lineStart = text.lastIndexOf('\n', start) + 1;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = text.length();
        String line = text.substring(lineStart, lineEnd).trim();
        if (line.length() > 300) line = line.substring(0, 300);
        return line;
    }

    private String firstLineContainingAny(String text, List<String> needles) {
        for (String line : text.split("\\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (needles.stream().anyMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)))) {
                String trimmed = line.trim();
                return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
            }
        }
        return null;
    }

    private String extractFirst(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }
}
