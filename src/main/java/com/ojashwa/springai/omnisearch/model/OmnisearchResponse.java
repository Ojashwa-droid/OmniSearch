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
public class OmnisearchResponse {
    private String query;
    private String answer;
    private List<String> sources;
    private List<String> followUpQuestions;
    private int sourceCount;
    private long processingTimeMs;
}
