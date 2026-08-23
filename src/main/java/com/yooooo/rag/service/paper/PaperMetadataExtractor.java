package com.yooooo.rag.service.paper;

import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.service.loader.ParseResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PaperMetadataExtractor {
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "\\b(10\\.\\d{4,9}/[-._;()/:A-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARXIV_PATTERN = Pattern.compile(
            "\\barXiv\\s*:?\\s*(\\d{4}\\.\\d{4,5}(?:v\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern KEYWORDS_PATTERN = Pattern.compile(
            "(?im)^\\s*(keywords|key words|index terms)\\s*[:\\-]?\\s*(.+)$");

    public boolean fillMissingMetadata(Paper paper, ParseResult parseResult, String fileName) {
        if (paper == null || parseResult == null) {
            return false;
        }

        boolean changed = false;
        String fullText = normalizeWhitespace(parseResult.getFullText());
        ParseResult.PaperMetadata structured = parseResult.getPaperMetadata();

        String structuredTitle = structured == null ? null : cleanTitle(structured.getTitle());
        String parsedTitle = !isBlank(structuredTitle) ? structuredTitle : cleanTitle(parseResult.getTitle());
        if (!isBlank(parsedTitle) && shouldReplaceTitle(paper.getTitle(), fileName)) {
            paper.setTitle(limit(parsedTitle, 500));
            changed = true;
        }
        if (structured != null) {
            if (isBlank(paper.getAuthors()) && !isBlank(structured.getAuthors())) {
                paper.setAuthors(limit(structured.getAuthors(), 1000));
                changed = true;
            }
            if (isBlank(paper.getAffiliations()) && !isBlank(structured.getAffiliations())) {
                paper.setAffiliations(limit(structured.getAffiliations(), 2000));
                changed = true;
            }
            if (paper.getYear() == null && structured.getPublicationYear() != null) {
                paper.setYear(structured.getPublicationYear());
                changed = true;
            }
        }
        if (isBlank(paper.getAbstractText())) {
            String abstractText = extractAbstract(parseResult, fullText);
            if (!isBlank(abstractText)) {
                paper.setAbstractText(limit(abstractText, 4000));
                changed = true;
            }
        }
        if (isBlank(paper.getKeywords())) {
            String keywords = extractKeywords(fullText);
            if (!isBlank(keywords)) {
                paper.setKeywords(limit(keywords, 1000));
                changed = true;
            }
        }
        if (isBlank(paper.getDoi())) {
            String doi = firstMatch(DOI_PATTERN, fullText);
            if (!isBlank(doi)) {
                paper.setDoi(limit(trimTrailingPunctuation(doi), 200));
                changed = true;
            }
        }
        if (isBlank(paper.getArxivId())) {
            String arxivId = firstMatch(ARXIV_PATTERN, fullText);
            if (!isBlank(arxivId)) {
                paper.setArxivId(limit(arxivId, 100));
                changed = true;
            }
        }
        if (paper.getYear() == null) {
            Integer year = extractYear(parseResult, fileName);
            if (year != null) {
                paper.setYear(year);
                changed = true;
            }
        }
        if (isBlank(paper.getAuthors())) {
            String authors = extractAuthors(parseResult, parsedTitle);
            if (!isBlank(authors)) {
                paper.setAuthors(limit(authors, 1000));
                changed = true;
            }
        }
        if (isBlank(paper.getAffiliations())) {
            String affiliations = extractAffiliations(parseResult, parsedTitle);
            if (!isBlank(affiliations)) {
                paper.setAffiliations(limit(affiliations, 2000));
                changed = true;
            }
        }

        return changed;
    }

    private String extractAbstract(ParseResult parseResult, String fullText) {
        List<String> parts = new ArrayList<>();
        if (parseResult.getPages() != null) {
            for (ParseResult.PageContent page : parseResult.getPages()) {
                if ("ABSTRACT".equalsIgnoreCase(page.getSectionType()) && !isBlank(page.getText())) {
                    parts.add(stripAbstractHeading(page.getText()));
                } else if (!parts.isEmpty() && page.getSectionType() != null) {
                    break;
                }
            }
        }
        String bySection = normalizeWhitespace(String.join(" ", parts));
        if (!isBlank(bySection)) {
            return trimAtKeywords(bySection);
        }

        Matcher matcher = Pattern.compile(
                "(?is)\\babstract\\b\\s*[:\\-]?\\s*(.+?)(\\bkeywords\\b|\\bkey words\\b|\\bindex terms\\b|\\b1\\.?\\s+introduction\\b|\\bintroduction\\b)")
                .matcher(fullText);
        if (matcher.find()) {
            return normalizeWhitespace(matcher.group(1));
        }
        return null;
    }

    private String extractKeywords(String text) {
        Matcher matcher = KEYWORDS_PATTERN.matcher(text);
        if (matcher.find()) {
            return trimTrailingPunctuation(normalizeWhitespace(matcher.group(2)));
        }
        return null;
    }

    private Integer extractYear(ParseResult parseResult, String fileName) {
        List<Integer> candidates = new ArrayList<>();
        addYearCandidates(candidates, fileName);
        if (parseResult != null && parseResult.getPages() != null) {
            for (int i = 0; i < Math.min(2, parseResult.getPages().size()); i++) {
                ParseResult.PageContent page = parseResult.getPages().get(i);
                if (page != null) {
                    addYearCandidates(candidates, page.getText());
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream().max(Integer::compareTo).orElse(null);
    }

    private void addYearCandidates(List<Integer> candidates, String text) {
        if (isBlank(text)) {
            return;
        }
        Matcher matcher = YEAR_PATTERN.matcher(text);
        int maxYear = LocalDate.now().getYear() + 1;
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year >= 1990 && year <= maxYear) {
                candidates.add(year);
            }
        }
    }

    private String extractAuthors(ParseResult parseResult, String title) {
        if (parseResult.getPages() == null || parseResult.getPages().isEmpty()) {
            return null;
        }
        String firstPage = parseResult.getPages().get(0).getText();
        if (isBlank(firstPage)) {
            return null;
        }

        String[] lines = firstPage.split("\\R");
        List<String> candidates = new ArrayList<>();
        int titleEndIndex = findTitleEndLineIndex(lines, title);
        boolean afterTitle = isBlank(title);
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            if (!afterTitle && titleEndIndex >= 0 && i > titleEndIndex) {
                afterTitle = true;
            }
            String line = normalizeWhitespace(rawLine);
            if (isBlank(line)) {
                continue;
            }
            if (!afterTitle) {
                if (sameLooseText(line, title)) {
                    afterTitle = true;
                }
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("abstract") || lower.contains("keywords") || lower.contains("introduction")) {
                break;
            }
            if (looksLikeAffiliation(line) || looksLikeMetadata(line)) {
                continue;
            }
            if (looksLikeAuthorLine(line)) {
                candidates.add(line);
                if (candidates.size() >= 3) {
                    break;
                }
            }
        }
        return candidates.isEmpty() ? null : String.join("; ", candidates);
    }

    private String extractAffiliations(ParseResult parseResult, String title) {
        if (parseResult.getPages() == null || parseResult.getPages().isEmpty()) {
            return null;
        }
        String firstPage = parseResult.getPages().get(0).getText();
        if (isBlank(firstPage)) {
            return null;
        }

        String[] lines = firstPage.split("\\R");
        List<String> affiliations = new ArrayList<>();
        int titleEndIndex = findTitleEndLineIndex(lines, title);
        boolean afterTitle = isBlank(title);
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            if (!afterTitle && titleEndIndex >= 0 && i > titleEndIndex) {
                afterTitle = true;
            }
            String line = normalizeWhitespace(rawLine);
            if (isBlank(line)) {
                continue;
            }
            if (!afterTitle) {
                if (sameLooseText(line, title)) {
                    afterTitle = true;
                }
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("abstract") || lower.matches(".*\\bintroduction\\b.*")) {
                break;
            }
            if (looksLikeInstitutionLine(line)) {
                String cleaned = line.replaceAll("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b", "")
                        .replaceAll("\\s+", " ").strip();
                if (!cleaned.isBlank() && !affiliations.contains(cleaned)) {
                    affiliations.add(cleaned);
                }
            }
        }
        return affiliations.isEmpty() ? null : String.join("; ", affiliations);
    }
    private int findTitleEndLineIndex(String[] lines, String title) {
        String target = normalizeForCompare(title);
        if (target.isBlank() || lines == null) {
            return -1;
        }
        int maxStart = Math.min(lines.length, 10);
        for (int start = 0; start < maxStart; start++) {
            StringBuilder candidate = new StringBuilder();
            for (int end = start; end < Math.min(lines.length, start + 5); end++) {
                candidate.append(normalizeForCompare(lines[end]));
                if (candidate.toString().equals(target)) {
                    return end;
                }
                if (candidate.length() > target.length()) {
                    break;
                }
            }
        }
        return -1;
    }
    private boolean shouldReplaceTitle(String currentTitle, String fileName) {
        if (isBlank(currentTitle)) {
            return true;
        }
        String current = stripPdfExtension(currentTitle);
        String file = stripPdfExtension(fileName);
        return sameLooseText(current, file) || current.toLowerCase(Locale.ROOT).startsWith("untitled");
    }

    private String stripAbstractHeading(String text) {
        return text.replaceFirst("(?is)^\\s*abstract\\s*[:\\-]?\\s*", "");
    }

    private String trimAtKeywords(String text) {
        return text.replaceFirst("(?is)\\s+(keywords|key words|index terms)\\s*[:\\-]?.*$", "").strip();
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean looksLikeAuthorLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.length() > 300 || line.length() < 3) {
            return false;
        }
        return line.contains(",")
                || line.contains(";")
                || lower.contains(" and ")
                || line.matches(".*\\p{Lu}[\\p{L}.'-]+\\s+\\p{Lu}[\\p{L}.'-]+.*");
    }

    private boolean looksLikeAffiliation(String line) {
        return looksLikeInstitutionLine(line) || line.contains("@");
    }

    private boolean looksLikeInstitutionLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("university")
                || lower.contains("institute")
                || lower.contains("department")
                || lower.contains("laboratory")
                || lower.contains("school of")
                || lower.contains("college")
                || lower.contains("research center")
                || lower.contains("research centre");
    }

    private boolean looksLikeMetadata(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("doi")
                || lower.contains("arxiv")
                || lower.contains("copyright")
                || lower.contains("conference")
                || lower.contains("journal");
    }

    private String cleanTitle(String title) {
        if (isBlank(title)) {
            return null;
        }
        return normalizeWhitespace(title);
    }

    private String stripPdfExtension(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("(?i)\\.pdf$", "");
    }

    private boolean sameLooseText(String a, String b) {
        return normalizeForCompare(a).equals(normalizeForCompare(b));
    }

    private String normalizeForCompare(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]+", "");
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    private String trimTrailingPunctuation(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceFirst("[\\s.,;:]+$", "");
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).strip();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
