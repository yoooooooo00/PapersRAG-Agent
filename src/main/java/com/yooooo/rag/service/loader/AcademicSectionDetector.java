package com.yooooo.rag.service.loader;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects common academic paper section headings from extracted PDF text.
 */
@Component
public class AcademicSectionDetector {
    private static final Pattern NUMBERED_PREFIX = Pattern.compile(
            "^(?:[0-9]+(?:\\.[0-9]+)*|[ivxlcdm]+)\\s*[.)-]?\\s+",
            Pattern.CASE_INSENSITIVE);

    public SectionMatch detectFromText(String text) {
        if (text == null || text.isBlank()) {
            return SectionMatch.empty();
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            SectionMatch match = detect(lines[i]);
            if (match.hasSectionType()) {
                return match;
            }
        }
        return SectionMatch.empty();
    }

    public SectionMatch detect(String headingCandidate) {
        if (headingCandidate == null) {
            return SectionMatch.empty();
        }
        String heading = normalizeHeading(headingCandidate);
        if (heading.length() < 2 || heading.length() > 120) {
            return SectionMatch.empty();
        }

        String normalized = NUMBERED_PREFIX.matcher(heading).replaceFirst("")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff ]", " ")
                .replaceAll("\\s+", " ")
                .strip();

        String type = detectType(normalized);
        if (type == null) {
            return SectionMatch.empty();
        }
        return new SectionMatch(heading, type);
    }

    public String inferSectionType(String sectionTitle, String content) {
        SectionMatch byTitle = detect(sectionTitle);
        if (byTitle.hasSectionType()) {
            return byTitle.sectionType();
        }
        return detectFromText(content).sectionType();
    }

    private String detectType(String value) {
        if (value.equals("abstract") || value.equals("summary") || value.equals("摘要")) return "ABSTRACT";
        if (value.equals("introduction") || value.equals("引言") || value.equals("导言")) return "INTRODUCTION";
        if (value.equals("related work") || value.equals("prior work") || value.equals("literature review")
                || value.equals("相关工作") || value.equals("文献综述")) return "RELATED_WORK";
        if (value.equals("background") || value.equals("preliminaries") || value.equals("背景") || value.equals("预备知识")) return "BACKGROUND";
        if (value.equals("method") || value.equals("methods") || value.equals("methodology")
                || value.equals("approach") || value.equals("model") || value.equals("proposed method")
                || value.equals("方法") || value.equals("模型") || value.equals("方法论")) return "METHOD";
        if (value.equals("experiments") || value.equals("experiment") || value.equals("experimental setup")
                || value.equals("evaluation") || value.equals("实验") || value.equals("实验设置") || value.equals("评估")) return "EXPERIMENTS";
        if (value.equals("results") || value.equals("result") || value.equals("analysis")
                || value.equals("结果") || value.equals("结果分析")) return "RESULTS";
        if (value.equals("discussion") || value.equals("讨论")) return "DISCUSSION";
        if (value.equals("limitations") || value.equals("limitation") || value.equals("局限") || value.equals("局限性")) return "LIMITATIONS";
        if (value.equals("conclusion") || value.equals("conclusions") || value.equals("conclusion and future work")
                || value.equals("future work") || value.equals("结论") || value.equals("总结")) return "CONCLUSION";
        if (value.equals("references") || value.equals("reference") || value.equals("bibliography")
                || value.equals("参考文献")) return "REFERENCES";
        if (value.equals("appendix") || value.equals("appendices") || value.equals("supplementary material")
                || value.equals("附录")) return "APPENDIX";
        return null;
    }

    private String normalizeHeading(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    public record SectionMatch(String sectionTitle, String sectionType) {
        static SectionMatch empty() {
            return new SectionMatch(null, null);
        }

        boolean hasSectionType() {
            return sectionType != null && !sectionType.isBlank();
        }
    }
}