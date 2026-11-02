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
public class FollowUpQuestions {
    private String originalQuery;
    private List<String> questions;
}
