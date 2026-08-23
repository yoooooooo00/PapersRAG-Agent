package com.yooooo.rag.service.loader;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Extracts scholarly header metadata through GROBID and safely returns null on fallback. */
@Component
@Slf4j
public class GrobidMetadataClient {
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private final WebClient webClient;

    @Value("${rag.parser.grobid.enabled:true}")
    private boolean enabled;

    @Value("${rag.parser.grobid.timeout:30s}")
    private Duration timeout;

    public GrobidMetadataClient(
            WebClient.Builder builder,
            @Value("${rag.parser.grobid.base-url:http://127.0.0.1:8070}") String baseUrl,
            @Value("${rag.parser.grobid.max-response-bytes:8388608}") int maxResponseBytes) {
        this.webClient = builder.clone().baseUrl(baseUrl.replaceFirst("/+$", ""))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ParseResult.PaperMetadata extract(byte[] pdf, String fileName) {
        if (!enabled || pdf == null || pdf.length == 0) return null;
        try {
            MultipartBodyBuilder form = new MultipartBodyBuilder();
            form.part("input", new NamedResource(pdf, fileName == null ? "document.pdf" : fileName))
                    .contentType(MediaType.APPLICATION_PDF);
            form.part("consolidateHeader", "0");
            form.part("includeRawAffiliations", "1");

            String tei = webClient.post()
                    .uri("/api/processHeaderDocument")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                    .body(BodyInserters.fromMultipartData(form.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
            return tei == null || tei.isBlank() ? null : parseTei(tei);
        } catch (Exception e) {
            log.warn("[GrobidMetadata] unavailable or failed fileName={} reason={}", fileName, e.getMessage());
            return null;
        }
    }

    ParseResult.PaperMetadata parseTei(String tei) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(tei)));
        var xpath = XPathFactory.newInstance().newXPath();

        String title = text(xpath.evaluate(
                "string((//*[local-name()='titleStmt']/*[local-name()='title'][1])[1])", document));
        Set<String> authors = new LinkedHashSet<>();
        NodeList authorNodes = (NodeList) xpath.evaluate(
                "//*[local-name()='sourceDesc']//*[local-name()='author']", document, XPathConstants.NODESET);
        for (int i = 0; i < authorNodes.getLength(); i++) {
            Node author = authorNodes.item(i);
            List<String> names = new ArrayList<>();
            NodeList parts = (NodeList) xpath.evaluate(
                    ".//*[local-name()='persName']/*[local-name()='forename' or local-name()='surname']",
                    author, XPathConstants.NODESET);
            for (int j = 0; j < parts.getLength(); j++) {
                String value = text(parts.item(j).getTextContent());
                if (!value.isBlank()) names.add(value);
            }
            String name = String.join(" ", names).replaceAll("\\s+", " ").strip();
            if (!name.isBlank()) authors.add(name);
        }

        Set<String> affiliations = new LinkedHashSet<>();
        NodeList affiliationNodes = (NodeList) xpath.evaluate(
                "//*[local-name()='sourceDesc']//*[local-name()='affiliation']",
                document, XPathConstants.NODESET);
        for (int i = 0; i < affiliationNodes.getLength(); i++) {
            Node affiliation = affiliationNodes.item(i);
            List<String> components = new ArrayList<>();
            NodeList componentNodes = (NodeList) xpath.evaluate(
                    ".//*[local-name()='orgName' or local-name()='settlement' or local-name()='country']",
                    affiliation, XPathConstants.NODESET);
            for (int j = 0; j < componentNodes.getLength(); j++) {
                String component = text(componentNodes.item(j).getTextContent());
                if (!component.isBlank() && !components.contains(component)) components.add(component);
            }
            String value = String.join(", ", components);
            if (value.isBlank()) value = text(affiliation.getTextContent());
            value = value.replaceAll("\\s+", " ").replaceAll("(?:,\\s*){2,}", ", ").strip();
            if (!value.isBlank()) affiliations.add(value);
        }

        String date = text(xpath.evaluate(
                "string((//*[local-name()='publicationStmt']//*[local-name()='date']/@when | "
                        + "//*[local-name()='publicationStmt']//*[local-name()='date']/text())[1])", document));
        Integer year = null;
        Matcher matcher = YEAR.matcher(date);
        if (matcher.find()) year = Integer.valueOf(matcher.group(1));

        return ParseResult.PaperMetadata.builder()
                .title(blankToNull(title))
                .authors(blankToNull(String.join("; ", authors)))
                .affiliations(blankToNull(String.join("; ", affiliations)))
                .publicationYear(year)
                .build();
    }

    private String text(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class NamedResource extends ByteArrayResource {
        private final String filename;
        private NamedResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }
        @Override public String getFilename() { return filename; }
    }
}