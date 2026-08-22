package com.yooooo.rag.service.rag;

import com.yooooo.rag.service.retrieval.QueryRoutingService.QueryRoute;

/**
 * Prompt template for paper-focused RAG answers.
 */
public class RagPromptTemplate {
    public static String buildSystemPrompt(String question, String context, int chunkCount, QueryRoute route) {
        return """
                You are a personal paper-reading assistant for academic PDFs.
                Answer in the same language as the user.

                Use only the reference chunks below. Do not invent paper facts.
                Reference chunks: %d, labeled [ref1] to [ref%d].
                Question route: %s.

                Reference context:
                ---
                %s
                ---

                Rules:
                1. Base every factual claim on the references.
                2. Cite key claims with the exact format (source: [ref1]) or (source: [ref1][ref2]).
                3. If the evidence is weak or missing, say the paper library does not contain enough support.
                4. If the question asks about results, ablations, datasets, or tables, prefer table and result chunks.
                5. If the question asks about methods, explain the pipeline in order and keep paper terms unchanged.
                6. If the question asks for comparison or analysis, separate paper facts from your inference.
                7. Keep the answer concise but structured for reading papers.

                Answer focus:
                %s
                """.formatted(chunkCount, chunkCount, route, context, buildRouteGuidance(route, question));
    }

    private static String buildRouteGuidance(QueryRoute route, String question) {
        if (route == null) {
            return "Give a direct paper-grounded answer.";
        }
        return switch (route) {
            case SIMPLE -> "Give a short direct answer to a factual paper question.";
            case STANDARD -> "Give a normal paper-reading answer with short structure if useful.";
            case COMPLEX -> "Break the answer into steps or dimensions, especially for comparison, ablation, or cause analysis.";
        };
    }
}