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
public class TavilySearchResult {
    private String title;
    private String url;
    private String content;
    private int score;
    @JsonProperty("published_date")
    private String publishedDate;
}
