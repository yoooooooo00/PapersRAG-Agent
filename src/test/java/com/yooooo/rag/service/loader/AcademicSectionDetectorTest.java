package com.yooooo.rag.service.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AcademicSectionDetectorTest {
    private final AcademicSectionDetector detector = new AcademicSectionDetector();

    @Test
    void returnsLastSectionForCrossPageInheritance() {
        var match = detector.detectLastFromText(
                "Abstract\nAbstract body\n1 Introduction\nIntroduction body");

        assertEquals("INTRODUCTION", match.sectionType());
        assertEquals("1 Introduction", match.sectionTitle());
    }

    @Test
    void normalizesCommonEnglishHeadingVariants() {
        assertEquals("BACKGROUND", detector.detect("3 Preliminary").sectionType());
        assertEquals("BACKGROUND", detector.detect("2 Preliminaries").sectionType());
        assertEquals("RELATED_WORK", detector.detect("II. Previous Work").sectionType());
        assertEquals("LIMITATIONS", detector.detect("Threats to Validity").sectionType());
    }

    @Test
    void recognizesArbitraryNumberedHeadingsWithoutDomainVocabulary() {
        var numbered = detector.detect("4.2 Constraint Calibration");
        var appendix = detector.detect("C.7 Sensitivity Sweep");
        assertTrue(numbered.hasHeading());
        assertEquals("4.2 Constraint Calibration", numbered.sectionTitle());
        assertTrue(appendix.hasHeading());
        assertEquals("C.7 Sensitivity Sweep", appendix.sectionTitle());
    }

    @Test
    void rejectsAffiliationsAndNumberedNarrativeAsHeadings() {
        assertFalse(detector.detect("1 Example University, 2 Another Institute").hasHeading());
        assertFalse(detector.detect("4 We use the snapshot selected for this experiment to ensure reproducibility.").hasHeading());
        assertFalse(detector.detect("3 Code and data are available at: https://example.org/project").hasHeading());
        assertFalse(detector.detect("A practical time decay model for streaming systems").hasHeading());
    }
}
