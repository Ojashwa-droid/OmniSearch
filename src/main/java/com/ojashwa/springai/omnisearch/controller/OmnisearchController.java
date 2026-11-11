package com.ojashwa.springai.omnisearch.controller;

import com.ojashwa.springai.omnisearch.agent.FollowUpQuestionsAgent;
import com.ojashwa.springai.omnisearch.controller.dtos.FollowUpRequest;
import com.ojashwa.springai.omnisearch.controller.dtos.FollowUpResponse;
import com.ojashwa.springai.omnisearch.controller.dtos.SearchRequest;
import com.ojashwa.springai.omnisearch.service.OmnisearchOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.web.bind.annotation.CrossOrigin;

@Slf4j
@RestController
@RequestMapping("/api/omnisearch")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OmnisearchController {

    private final OmnisearchOrchestrator orchestrator;
    private final FollowUpQuestionsAgent followUpAgent;

    @PostMapping(value = "/conversation", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> search(@RequestBody SearchRequest request) {
        log.info("Received search request {} with conversationId as: {}", request.getQuery(),
                request.getConversationId());
        return orchestrator.searchWithStreaming(request.getQuery(), request.getConversationId());
    }

    @PostMapping("/followups")
    public ResponseEntity<FollowUpResponse> getFollowUps(@RequestBody FollowUpRequest request) {
        FollowUpResponse followUpResponse = followUpAgent.generateFollowUps(request.originalQuery(), request.answer());

        log.info("Generated follow up questions: {}", followUpResponse.followUpQuestions());

        return ResponseEntity.ok(followUpResponse);
    }

    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("Omnisearch API is running");
    }

    @PostMapping(value = "/conversation/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> searchStream(@RequestBody SearchRequest request) {
        log.info("Received streaming search request: {}", request.getQuery());

        return orchestrator.searchWithStreaming(request.getQuery(), request.getConversationId())
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .doOnComplete(() -> {
                    log.info("Stream completed for query: {}", request.getQuery());
                });
    }
}
