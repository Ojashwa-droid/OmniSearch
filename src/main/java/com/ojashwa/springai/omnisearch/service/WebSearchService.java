package com.ojashwa.springai.omnisearch.service;

import com.ojashwa.springai.omnisearch.model.TavilyResponse;
import com.ojashwa.springai.omnisearch.model.TavilySearchRequest;
import com.ojashwa.springai.omnisearch.model.TavilySearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class WebSearchService {

    private final WebClient webClient;

    public WebSearchService(
            WebClient.Builder webClientBuilder,
            @Value("${omnisearch.tavily.api-url:https://api.tavily.com/search}") String tavilyApiUrl,
            @Value("${omnisearch.tavily.api-key}") String tavilyApiKey
    ) {
        this.webClient = webClientBuilder
                .baseUrl(tavilyApiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + tavilyApiKey)
                .build();
    }

    public Mono<TavilyResponse> search(String query) {
        TavilySearchRequest tavilySearchRequest = TavilySearchRequest.builder()
                .query(query)
                .maxResults(10)
                .searchDepth("basic")
                .includeAnswer(true)
                .includeRawContent(true)
                .build();
        return search(tavilySearchRequest);
    }

    public Mono<TavilyResponse> search(TavilySearchRequest request) {
        log.info("Performing web search for query: {}", request.getQuery());

        return webClient.post()
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TavilyResponse.class)
                .doOnSuccess(response -> log.info("Search completed with {} results",
                        response.getResults() != null ? response.getResults().size() : 0))
                // Catching the 429 Rate Limit Error specifically
                .onErrorResume(WebClientResponseException.TooManyRequests.class, error -> {
                    log.warn("Tavily API Rate Limit Exceeded (429). Injecting Fallback Context.");
                    return Mono.just(createFallbackResponse(request.getQuery()));
                })
                .onErrorResume(Exception.class, error -> {
                    log.error("Tavily Search Failed: {}. Injecting Fallback Context.", error.getMessage());
                    return Mono.just(createFallbackResponse(request.getQuery()));
                });
    }

    /**
     * Creates a simulated response so the LLM pipeline doesn't crash during the demo.
     */
    private TavilyResponse createFallbackResponse(String query) {
        TavilySearchResult mockResult = TavilySearchResult.builder()
                .title("Simulated Search Result")
                .url("https://omnisearch.local/demo-fallback")
                .content("Due to high network traffic, live web search is temporarily bypassed. " +
                        "This is a simulated context for the query: " + query + ". " +
                        "Omnisearch is currently relying on the LLM's internal knowledge base to answer this question.")
                .build();

        return TavilyResponse.builder()
                .query(query)
                .results(List.of(mockResult))
                .build();
    }
}
