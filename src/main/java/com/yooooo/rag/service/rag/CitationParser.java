package com.yooooo.rag.service.rag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Extracts citation markers from model answers so source metadata can be shown.
 */
@Component
@Slf4j
public class CitationParser {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[ref(\\d+)]");

    public Set<Integer> extractCitedIndices(String answer) {
        Set<Integer> cited = new LinkedHashSet<>();
        if (answer == null || answer.isBlank()) {
            return cited;
        }

        Matcher m = CITATION_PATTERN.matcher(answer);
        while (m.find()) {
            try {
                cited.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        return cited;
    }

    public String cleanCitations(String answer) {
        if (answer == null) {
            return "";
        }
        return answer
                .replaceAll("\\(source: (?:\\[ref\\d+])+\\)", "")
                .replaceAll("\\[ref\\d+]", "")
                .replaceAll("\\s+", " ")
                .strip();
    }
}