package com.ojashwa.springai.omnisearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TavilyResponse {
    private String answer;
    @JsonProperty("query")
    private String query;
    private List<TavilySearchResult> results;
}
