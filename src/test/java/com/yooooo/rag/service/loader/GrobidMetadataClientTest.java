package com.yooooo.rag.service.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class GrobidMetadataClientTest {
    @Test
    void parsesAuthorsAffiliationsAndPublicationYearFromTei() throws Exception {
        String tei = """
                <TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc>
                  <titleStmt><title>General English Paper</title></titleStmt>
                  <publicationStmt><date when="2024-06-01"/></publicationStmt>
                  <sourceDesc><biblStruct><analytic>
                    <author><persName><forename>Jane</forename><surname>Doe</surname></persName>
                      <affiliation><orgName type="department">Department of AI</orgName>
                        <orgName type="institution">Example University</orgName><country>USA</country></affiliation>
                    </author>
                    <author><persName><forename>John</forename><surname>Smith</surname></persName></author>
                  </analytic></biblStruct></sourceDesc>
                </fileDesc></teiHeader></TEI>
                """;
        GrobidMetadataClient client = new GrobidMetadataClient(WebClient.builder(), "http://localhost:8070", 8388608);

        ParseResult.PaperMetadata metadata = client.parseTei(tei);

        assertEquals("General English Paper", metadata.getTitle());
        assertEquals("Jane Doe; John Smith", metadata.getAuthors());
        assertEquals("Department of AI, Example University, USA", metadata.getAffiliations());
        assertEquals(2024, metadata.getPublicationYear());
    }
}