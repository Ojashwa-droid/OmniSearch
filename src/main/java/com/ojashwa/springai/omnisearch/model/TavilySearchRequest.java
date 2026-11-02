package com.ojashwa.springai.omnisearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchRequest {
    private String query;
    @Builder.Default
    private int maxResults = 10;
    @Builder.Default
    private String searchDepth = "basic"; // basic or advanced
    @Builder.Default
    private boolean includeAnswer = false;
    @Builder.Default
    private boolean includeRawContent = false;
    @Builder.Default
    private String topics = "general"; // general, news, finance
}
