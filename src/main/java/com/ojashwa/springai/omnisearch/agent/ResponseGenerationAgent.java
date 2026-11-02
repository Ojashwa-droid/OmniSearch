package com.ojashwa.springai.omnisearch.agent;

import com.ojashwa.springai.omnisearch.model.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResponseGenerationAgent {

    private final ChatClient chatClient;

    @Value("classpath:promptTemplates/systemPromptTemplate.st")
    Resource systemPrompt;

    @Value(value = "classpath:promptTemplates/userPromptTemplate.st")
    Resource userPrompt;

    /**
     * Matches a markdown link of the form [Title](URL) so we can extract the URL
     * that the orchestrator stored in relevantSources.
     */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    public ResponseGenerationAgent(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .temperature(0.7)
                        .model("gemini-3.5-flash-lite")
                        .build())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Flux<String> generateResponse(SearchContext context, String conversationId) {
        log.info("Generating response for query: {} with conversationId: {}", context.getOriginalQuery(), conversationId);

        // Build a plain numbered sources list that exposes the raw URL.
        // Format: "1. Title - https://url"
        // The system prompt instructs the model to cite as [[N]](URL), and having
        // the bare URL in the list means it can copy it verbatim without guessing.
        AtomicInteger index = new AtomicInteger(1);
        String sourcesText = context.getRelevantSources().stream()
                .map(source -> {
                    int n = index.getAndIncrement();
                    Matcher m = MD_LINK.matcher(source);
                    if (m.find()) {
                        String title = m.group(1);
                        String url   = m.group(2);
                        return n + ". " + title + " - " + url;
                    }
                    return n + ". " + source;
                })
                .collect(Collectors.joining("\n"));

        return chatClient.prompt()
                .system(promptSystemSpec -> promptSystemSpec.text(systemPrompt))
                .user(u -> u.text(userPrompt)
                        .param("query", context.getOriginalQuery())
                        .param("context", context.getAggregatedContent())
                        .param("sources", sourcesText)
                )
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}