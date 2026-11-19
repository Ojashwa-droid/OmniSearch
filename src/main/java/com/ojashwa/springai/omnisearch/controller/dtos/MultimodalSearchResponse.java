package com.ojashwa.springai.omnisearch.controller.dtos;

import com.ojashwa.springai.omnisearch.model.TavilySearchResult;

import java.util.List;

public record MultimodalSearchResponse(
        String query,
        String conversationId,
        String answer,
        List<TavilySearchResult> sources,
        List<AttachmentTranscript> attachments,
        int sourceCount,
        long processingTimeMs) {

    public record AttachmentTranscript(String filename, String kind, String extractedText) { }
}
