package com.ojashwa.springai.omnisearch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchContext {
    private String originalQuery;
    private String processedQuery;
    private List<String> relevantSources;
    private String aggregatedContent;
    private int sourceCount;
}
