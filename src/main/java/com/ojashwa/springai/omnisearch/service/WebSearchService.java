package com.ojashwa.springai.omnisearch.service;

import com.ojashwa.springai.omnisearch.model.TavilyResponse;
import com.ojashwa.springai.omnisearch.model.TavilySearchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;


@Slf4j
@Service
public class WebSearchService {

    private final WebClient webClient;
    private final String tavilyApiKey;

    public WebSearchService(
            WebClient.Builder webClientBuilder,
            @Value("${omnisearch.tavily.api-url:https://api.tavily.com/search}") String tavilyApiUrl,
            @Value("${omnisearch.tavily.api-key:}") String tavilyApiKey
    ) {
        this.tavilyApiKey = tavilyApiKey;
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
        long startedAt = System.nanoTime();
        log.info("Tavily request started: queryCharacters={}, maxResults={}, searchDepth={}",
                request.getQuery() == null ? 0 : request.getQuery().length(), request.getMaxResults(), request.getSearchDepth());

        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            log.error("Tavily request blocked because TAVILY_API_KEY is not configured");
            return Mono.error(new IllegalStateException("Web search is not configured. Set TAVILY_API_KEY."));
        }

        return webClient.post()
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TavilyResponse.class)
                .timeout(Duration.ofSeconds(30))
                .doOnSuccess(response -> log.info("Tavily request completed: resultCount={}, durationMs={}",
                        response.getResults() != null ? response.getResults().size() : 0, elapsedMillis(startedAt)))
                .doOnError(error -> log.error("Tavily request failed: errorType={}, durationMs={}",
                        error.getClass().getSimpleName(), elapsedMillis(startedAt)));
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
