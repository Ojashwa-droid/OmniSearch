package com.ojashwa.springai.omnisearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojashwa.springai.omnisearch.agent.FollowUpQuestionsAgent;
import com.ojashwa.springai.omnisearch.agent.ResponseGenerationAgent;
import com.ojashwa.springai.omnisearch.model.SearchContext;
import com.ojashwa.springai.omnisearch.model.TavilyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OmnisearchOrchestrator {

    private final WebSearchService webSearchService;
    private final ResponseGenerationAgent responseGenerationAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OmnisearchOrchestrator(
            WebSearchService webSearchService,
            ResponseGenerationAgent responseGenerationAgent,
            FollowUpQuestionsAgent followUpQuestionsAgent
    ) {
        this.webSearchService = webSearchService;
        this.responseGenerationAgent = responseGenerationAgent;
    }

    public Flux<String> searchWithStreaming(String query, String conversationId) {
        log.info("Starting streamlined search pipeline for: {}", query);

        return webSearchService.search(query)
                .flatMapMany(searchResponse -> {
                    // 1. Build the context manually using standard Java, NO LLM REQUIRED.
                    // This takes 1 millisecond instead of 16 seconds.
                    SearchContext context = buildContext(query, searchResponse);

                    String sourcesHeader = "";
                    try {
                        if (searchResponse.getResults() != null) {
                            sourcesHeader = "<!--SOURCES:" + objectMapper.writeValueAsString(searchResponse.getResults()) + "-->\n";
                        }
                    } catch (Exception e) {
                        log.warn("Failed to serialize search sources to JSON header", e);
                    }

                    log.info("Search context built directly. Starting LLM response stream immediately.");

                    // 2. Start streaming the final answer to the frontend immediately.
                    Flux<String> stream = responseGenerationAgent.generateResponse(context, conversationId);
                    if (!sourcesHeader.isEmpty()) {
                        return Flux.concat(Flux.just(sourcesHeader), stream);
                    }
                    return stream;
                });
    }

    private SearchContext buildContext(String query, TavilyResponse response) {
        List<String> sources = response.getResults().stream()
                .map(r -> String.format("[%s](%s)", r.getTitle(), r.getUrl()))
                .collect(Collectors.toList());

        String aggregated = response.getResults().stream()
                .map(r -> r.getTitle() + ":\n" + r.getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        return SearchContext.builder()
                .originalQuery(query)
                .processedQuery(query)
                .relevantSources(sources)
                .aggregatedContent(aggregated)
                .sourceCount(response.getResults().size())
                .build();
    }
}