package com.yooooo.rag.service.loader;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Detects and normalizes common English academic section headings. */
@Component
public class AcademicSectionDetector {
    private static final Pattern NUMBERED_PREFIX = Pattern.compile(
            "^(?:(?:section|chapter|part|appendix)\\s+)?(?:[0-9]+(?:\\.[0-9]+)*|[ivxlcdm]+|[A-Z])\\s*[.)-]?\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURAL_HEADING = Pattern.compile(
            "^(?:(?:section|chapter|part|appendix)\\s+)?"
                    + "(?:[0-9]+(?:\\.[0-9]+){0,4}|[A-Z](?:\\.[0-9]+){0,4}|[IVXLCDM]+)"
                    + "\\s*[.)-]?\\s+(.{2,120})$");
    private static final List<SectionRule> RULES = List.of(
            rule("ABSTRACT", "abstract|summary|executive summary"),
            rule("INTRODUCTION", "introduction|overview|motivation"),
            rule("RELATED_WORK", "related works?|prior works?|previous works?|literature review|state of the art"),
            rule("BACKGROUND", "background|preliminar(?:y|ies)|problem formulation|problem definition|definitions|notation"),
            rule("METHOD", "methods?|methodology|approach|proposed (?:method|approach)|model|framework|architecture|system design"),
            rule("EXPERIMENTS", "experiments?|experimental (?:setup|settings|evaluation)|evaluation|implementation details|empirical study"),
            rule("RESULTS", "results?|analysis|findings|quantitative results|qualitative results"),
            rule("DISCUSSION", "discussion|implications"),
            rule("LIMITATIONS", "limitations?|threats to validity"),
            rule("CONCLUSION", "conclusions?|concluding remarks|conclusion and future work|future work"),
            rule("REFERENCES", "references?|bibliography"),
            rule("APPENDIX", "appendix|appendices|supplementary material|supplemental material")
    );

    public SectionMatch detectFromText(String text) {
        if (text == null || text.isBlank()) return SectionMatch.empty();
        for (String line : text.split("\\R")) {
            SectionMatch match = detect(line);
            if (match.hasSectionType()) return match;
        }
        return SectionMatch.empty();
    }

    public SectionMatch detectLastFromText(String text) {
        if (text == null || text.isBlank()) return SectionMatch.empty();
        SectionMatch last = SectionMatch.empty();
        for (String line : text.split("\\R")) {
            SectionMatch match = detect(line);
            if (match.hasSectionType()) last = match;
        }
        return last;
    }

    public SectionMatch detect(String headingCandidate) {
        if (headingCandidate == null) return SectionMatch.empty();
        String heading = headingCandidate.replaceAll("\\s+", " ").strip();
        if (heading.length() < 2 || heading.length() > 140) return SectionMatch.empty();

        String normalized = NUMBERED_PREFIX.matcher(heading).replaceFirst("")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[.:;]+$", "")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff -]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        for (SectionRule rule : RULES) {
            if (rule.pattern().matcher(normalized).matches()) {
                return new SectionMatch(heading, rule.type());
            }
        }
        if (normalized.equals("\u6458\u8981")) return new SectionMatch(heading, "ABSTRACT");
        if (normalized.equals("\u5f15\u8a00") || normalized.equals("\u5bfc\u8a00")) return new SectionMatch(heading, "INTRODUCTION");
        if (normalized.equals("\u53c2\u8003\u6587\u732e")) return new SectionMatch(heading, "REFERENCES");
        if (normalized.equals("\u9644\u5f55")) return new SectionMatch(heading, "APPENDIX");
        if (isStructuralHeading(heading)) {
            return new SectionMatch(heading, inferBroadType(normalized));
        }
        return SectionMatch.empty();
    }

    private boolean isStructuralHeading(String heading) {
        var matcher = STRUCTURAL_HEADING.matcher(heading);
        if (!matcher.matches()) return false;
        String title = matcher.group(1).strip();
        if (title.length() > 100 || title.matches(".*[.!?]$")
                || title.contains("@") || title.contains("://") || title.contains("{") || title.contains("}")
                || title.matches(".*[,;]\\s*\\d+\\b.*")) {
            return false;
        }
        String[] words = title.split("\\s+");
        if (words.length > 14) return false;
        long letters = title.codePoints().filter(Character::isLetter).count();
        return letters >= 2 && letters * 2 >= title.codePointCount(0, title.length())
                && hasHeadingLikeCapitalization(words);
    }

    private boolean hasHeadingLikeCapitalization(String[] words) {
        int meaningful = 0;
        int headingStyle = 0;
        for (String word : words) {
            String cleaned = word.replaceAll("^[^A-Za-z]+|[^A-Za-z]+$", "");
            if (cleaned.isBlank() || cleaned.matches("(?i)^(a|an|and|as|at|by|for|from|in|of|on|or|the|to|via|with)$")) {
                continue;
            }
            meaningful++;
            if (Character.isUpperCase(cleaned.charAt(0)) || cleaned.equals(cleaned.toUpperCase(Locale.ROOT))) {
                headingStyle++;
            }
        }
        return meaningful >= 1 && headingStyle * 10 >= meaningful * 7;
    }

    private String inferBroadType(String normalized) {
        if (normalized.matches(".*\\b(?:result|results|analysis|comparison|finding|findings)\\b.*")) return "RESULTS";
        if (normalized.matches(".*\\b(?:experiment|experimental|evaluation|metric|metrics|dataset|datasets|baseline|baselines)\\b.*")) return "EXPERIMENTS";
        if (normalized.matches(".*\\b(?:method|methodology|approach|model|framework|reasoning|generation|adaptation)\\b.*")) return "METHOD";
        return null;
    }

    public String inferSectionType(String sectionTitle, String content) {
        SectionMatch byTitle = detect(sectionTitle);
        return byTitle.hasSectionType() ? byTitle.sectionType() : detectFromText(content).sectionType();
    }

    private static SectionRule rule(String type, String alternatives) {
        return new SectionRule(type, Pattern.compile("(?:" + alternatives + ")", Pattern.CASE_INSENSITIVE));
    }

    private record SectionRule(String type, Pattern pattern) {}

    public record SectionMatch(String sectionTitle, String sectionType) {
        static SectionMatch empty() { return new SectionMatch(null, null); }
        public boolean hasHeading() { return sectionTitle != null && !sectionTitle.isBlank(); }
        boolean hasSectionType() { return sectionType != null && !sectionType.isBlank(); }
    }
}