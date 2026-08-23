package com.yooooo.rag.service.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PdfParserCaptionTest {
    @Test
    void joinsMultiLineFigureCaptionWithoutConsumingBody() {
        String input = lines(
                "Figure 1: A framework that",
                "combines retrieval and generation.",
                "Body starts here.");

        String normalized = normalize(input);

        assertTrue(normalized.contains(
                "[FIGURE_CAPTION]Figure 1: A framework that combines retrieval and generation.[/FIGURE_CAPTION]"));
        assertTrue(normalized.endsWith("Body starts here."));
    }

    @Test
    void recognizesGenericSingleSpaceMultiLevelTableWithoutVocabulary() {
        String input = lines(
                "Table 7: Comparison across two collections.",
                "Systems Collection-A Collection-B",
                "Score Top-1 Score Top-1",
                "Alpha System 0.450 0.345 0.503 0.395",
                "Beta System 0.471 0.369 0.521 0.416",
                "The following paragraph explains the result.");

        String normalized = normalize(input);

        assertTrue(normalized.contains("[TABLE_CAPTION]Table 7: Comparison across two collections.[/TABLE_CAPTION]"));
        assertTrue(normalized.contains("| Systems | Collection-A | | Collection-B | |"));
        assertTrue(normalized.contains("| Score | Top-1 | Score | Top-1 |"));
        assertTrue(normalized.contains("| Beta System | 0.471 | 0.369 | 0.521 | 0.416 |"));
        assertTrue(normalized.endsWith("The following paragraph explains the result."));
    }

    @Test
    void rejoinsRepeatedHeaderSuffixesAndSharedUnitsByShape() {
        String input = lines(
                "Table 8: General inventory statistics.",
                "Items  #Archived  Records  #Current  Records  #Future  Records  Resolution",
                "Series-A  6,869  74,845  8,514  24  hours",
                "Series-B  10,094  368,868  46,302  24  hours",
                "Series-C  23,033  373,018  45,995  24  hours");

        String normalized = normalize(input);

        assertTrue(normalized.contains(
                "| Items | #Archived Records | #Current Records | #Future Records | Resolution |"));
        assertTrue(normalized.contains(
                "| Series-B | 10,094 | 368,868 | 46,302 | 24 hours |"));
    }

    @Test
    void keepsArbitraryTwoColumnKeyValueTableWithoutKnownLabels() {
        String input = lines(
                "Table 9: Deployment settings.",
                "Property    Value",
                "Maximum retry count    five attempts",
                "Storage location    north region",
                "Compression policy    adaptive mode");

        String normalized = normalize(input);

        assertTrue(normalized.contains("| Property | Value |"));
        assertTrue(normalized.contains("| Maximum retry count | five attempts |"));
        assertTrue(normalized.contains("| Compression policy | adaptive mode |"));
    }

    @Test
    void associatesCaptionPlacedBelowTable() {
        String input = lines(
                "Name    Value",
                "Alpha    10",
                "Beta    20",
                "Table 10: Caption below the table.");

        String normalized = normalize(input);

        int captionIndex = normalized.indexOf("[TABLE_CAPTION]Table 10: Caption below the table.[/TABLE_CAPTION]");
        int tableIndex = normalized.indexOf("[TABLE]", captionIndex);
        assertTrue(captionIndex >= 0 && tableIndex > captionIndex);        assertTrue(normalized.contains("| Beta | 20 |"));
    }

    @Test
    void doesNotPromoteAlignedNarrativeOrFrontMatterWithoutCaption() {
        String input = lines(
                "Large Language Models-guided    Dynamic Adaptation",
                "for Temporal Knowledge Graph    Reasoning",
                "Alice Smith, Bob Jones    Example University",
                "Our limitations can be summarized    as follows",
                "The method does not consider semantics    which may reduce quality",
                "Future work will improve prompts    for different datasets");

        String normalized = normalize(input);

        assertFalse(normalized.contains("[TABLE]"));
    }

    @Test
    void doesNotPromoteNarrativeWithScatteredNumbers() {
        String input = lines(
                "The dataset covers events from    2005 to 2015",
                "Hit at N measures whether rank    1, 3, or 10",
                "The values range between    0 and 1",
                "These definitions explain    evaluation behavior");

        String normalized = normalize(input);

        assertFalse(normalized.contains("[TABLE]"));
    }

    @Test
    void recognizesStableUncaptionedNumericTable() {
        String input = lines(
                "System    Score A    Score B",
                "Alpha    0.42    0.58",
                "Beta    0.47    0.61");

        String normalized = normalize(input);

        assertTrue(normalized.contains("[TABLE]"));
        assertTrue(normalized.contains("| Beta | 0.47 | 0.61 |"));
    }
    @Test
    void doesNotTreatFigureReferenceAsCaption() {
        String normalized = normalize("Figure 8, the performance exhibits an increasing trend.");

        assertFalse(normalized.contains("[FIGURE_CAPTION]"));
    }

    @Test
    void doesNotTreatConsecutiveNumericProseAsTable() {
        String input = lines(
                "The parameters alpha use values 0.1 0.2",
                "The parameters beta use values 0.3 0.4");

        String normalized = normalize(input);

        assertFalse(normalized.contains("[TABLE]"));
    }

    @Test
    void alignsSparseGroupLabelsAndRepeatedMetricHeaders() {
        String input = lines(
                "Table 11: Generic grouped comparison.",
                "Type    System    Train    Set-A    Set-B",
                "Score    Top-1    Score    Top-1",
                "Alpha    yes    0.41    0.31    0.51    0.39",
                "Group-X    Beta    yes    0.47    0.36    0.58    0.42",
                "Gamma    no    0.44    0.33    0.54    0.40");

        String normalized = normalize(input);

        assertTrue(normalized.contains("| Type | System | Train | Set-A | | Set-B | |"), normalized);
        assertTrue(normalized.contains("| | | | Score | Top-1 | Score | Top-1 |"), normalized);
        assertTrue(normalized.contains("| | Alpha | yes | 0.41 | 0.31 | 0.51 | 0.39 |"), normalized);
        assertTrue(normalized.contains("| Group-X | Beta | yes | 0.47 | 0.36 | 0.58 | 0.42 |"), normalized);
    }

    @Test
    void mergesOverSegmentedRowLabelsUsingTrailingValueShape() {
        String input = lines(
                "Table 12: Generic variants.",
                "Variant    Set-A    Set-B",
                "Score    Top-1    Score    Top-1",
                "Model Z    with    history    0.45    0.34    0.50    0.39",
                "Model Z    current    only    0.47    0.36    0.53    0.41");

        String normalized = normalize(input);

        assertTrue(normalized.contains("| Model Z with history | 0.45 | 0.34 | 0.50 | 0.39 |"), normalized);
        assertTrue(normalized.contains("| Model Z current only | 0.47 | 0.36 | 0.53 | 0.41 |"), normalized);
    }
    @Test
    void isolatesInlineTwoColumnFigureCaptionWithoutLosingBody() {
        String input = lines(
                "The left column continues here    Figure 4: Comparison across",
                "and remains body content          several evaluation groups.",
                "The next paragraph remains available.");

        String normalized = normalize(input);

        assertTrue(normalized.contains("[FIGURE_CAPTION]Figure 4: Comparison across several evaluation groups.[/FIGURE_CAPTION]"), normalized);
        assertTrue(normalized.contains("The left column continues here"), normalized);
        assertTrue(normalized.contains("and remains body content"), normalized);
    }

    @Test
    void rejoinsSafeLowercaseLineEndHyphenation() {
        String normalized = normalize(lines("The method adapts contin-", "uously over time."));

        assertTrue(normalized.contains("continuously over time."), normalized);
        assertFalse(normalized.contains("contin-"), normalized);
    }
    private String normalize(String input) {
        PdfParser parser = new PdfParser(new AcademicSectionDetector());
        return ReflectionTestUtils.invokeMethod(parser, "normalizeTableRuns", input);
    }

    private String lines(String... values) {
        return String.join(System.lineSeparator(), values);
    }
}