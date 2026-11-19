package com.ojashwa.springai.omnisearch.agent;

import com.ojashwa.springai.omnisearch.model.SearchContext;
import com.ojashwa.springai.omnisearch.model.AttachmentEvidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResponseGenerationAgent {

    private final ChatClient chatClient;
    private final ChatClient multimodalChatClient;
    private final ChatMemory chatMemory;

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
        this.chatMemory = chatMemory;
        this.multimodalChatClient = builder
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .temperature(0.7)
                        .model("gemini-3.5-flash-lite")
                        .build())
                .build();
        this.chatClient = builder
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .temperature(0.7)
                        .model("gemini-3.5-flash-lite")
                        .build())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Flux<String> generateResponse(SearchContext context, String conversationId) {
        long startedAt = System.nanoTime();
        StringBuilder answerText = new StringBuilder();
        log.info("Text answer generation started: conversationId={}, queryCharacters={}, sourceCount={}",
                conversationId, context.getOriginalQuery() == null ? 0 : context.getOriginalQuery().length(), context.getSourceCount());

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
                .content()
                .doOnNext(answerText::append)
                .doOnComplete(() -> log.info("Text answer generation completed: conversationId={}, answerCharacters={}, durationMs={}",
                        conversationId, answerText.length(), elapsedMillis(startedAt)))
                .doOnError(error -> log.warn("Text answer generation failed: conversationId={}, errorType={}, durationMs={}",
                        conversationId, error.getClass().getSimpleName(), elapsedMillis(startedAt)))
                .doOnCancel(() -> log.info("Text answer generation cancelled: conversationId={}, streamedCharacters={}",
                        conversationId, answerText.length()));
    }

    public Flux<String> generateMultimodalResponse(
            SearchContext context, List<AttachmentEvidence> attachments, String conversationId) {
        String safeConversationId = StringUtils.hasText(conversationId) ? conversationId : "default";
        List<Message> messages = new ArrayList<>();
        for (Message prior : chatMemory.get(safeConversationId)) {
            if (prior instanceof UserMessage userMessage) {
                messages.add(new UserMessage(userMessage.getText()));
            } else {
                messages.add(prior);
            }
        }

        String sourcesText = numberedSources(context);
        String sanitizedTurn = buildSanitizedTurn(context, attachments);
        StringBuilder completedAnswer = new StringBuilder();
        long startedAt = System.nanoTime();
        log.info("Multimodal answer generation started: conversationId={}, queryCharacters={}, sourceCount={}, attachmentCount={}, mediaCount={}",
                safeConversationId, context.getOriginalQuery() == null ? 0 : context.getOriginalQuery().length(),
                context.getSourceCount(), attachments.size(), attachments.stream().filter(AttachmentEvidence::hasMedia).count());
        return multimodalChatClient.prompt()
                .system(promptSystemSpec -> promptSystemSpec.text(systemPrompt))
                .messages(messages)
                .user(userSpec -> {
                    userSpec.text(userPrompt)
                            .param("query", context.getOriginalQuery())
                            .param("context", context.getAggregatedContent())
                            .param("sources", sourcesText);
                    attachments.stream().filter(AttachmentEvidence::hasMedia).forEach(item ->
                            userSpec.media(MimeTypeUtils.parseMimeType(item.mimeType()), new ByteArrayResource(item.mediaBytes()) {
                                @Override
                                public String getFilename() {
                                    return item.filename();
                                }
                            }));
                })
                .stream()
                .content()
                .doOnNext(completedAnswer::append)
                .doOnComplete(() -> {
                    if (completedAnswer.length() > 0) {
                        chatMemory.add(safeConversationId, List.of(
                                new UserMessage(sanitizedTurn),
                                new AssistantMessage(completedAnswer.toString())));
                    }
                    log.info("Multimodal answer generation completed: conversationId={}, answerCharacters={}, durationMs={}",
                            safeConversationId, completedAnswer.length(), elapsedMillis(startedAt));
                })
                .doOnError(error -> log.warn("Multimodal answer generation failed: conversationId={}, errorType={}, durationMs={}",
                        safeConversationId, error.getClass().getSimpleName(), elapsedMillis(startedAt)))
                .doOnCancel(() -> log.info("Multimodal answer generation cancelled: conversationId={}, streamedCharacters={}",
                        safeConversationId, completedAnswer.length()));
    }

    private String numberedSources(SearchContext context) {
        AtomicInteger index = new AtomicInteger(1);
        return context.getRelevantSources().stream()
                .map(source -> {
                    int number = index.getAndIncrement();
                    Matcher matcher = MD_LINK.matcher(source);
                    if (matcher.find()) return number + ". " + matcher.group(1) + " - " + matcher.group(2);
                    return number + ". " + source;
                })
                .collect(Collectors.joining("\n"));
    }

    private String buildSanitizedTurn(SearchContext context, List<AttachmentEvidence> attachments) {
        String attachmentText = attachments.stream()
                .map(item -> item.filename() + " (" + item.kind() + ")"
                        + (StringUtils.hasText(item.extractedText()) ? ":\n" + item.extractedText() : ""))
                .collect(Collectors.joining("\n\n"));
        return "Question: " + context.getOriginalQuery() + "\n\nAttachment names and extracted text:\n"
                + attachmentText + "\n\nResearch context:\n" + context.getAggregatedContent();
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
