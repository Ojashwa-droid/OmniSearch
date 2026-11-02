package com.ojashwa.springai.omnisearch.agent;

import com.ojashwa.springai.omnisearch.controller.dtos.FollowUpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FollowUpQuestionsAgent {

    private final ChatClient chatClient;
    @Value("classpath:promptTemplates/followupSystemPromptTemplate.st")
    Resource followupSystemPrompt;

    @Value(value = "classpath:promptTemplates/followupUserPromptTemplate.st")
    Resource followupUserPrompt;


    public FollowUpQuestionsAgent(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .model("gemini-3.5-flash-lite")
                        .temperature(0.6)
                        .responseMimeType("application/json") // Forcing Gemini to output raw JSON
                        .build())
                .build();
    }

    public FollowUpResponse generateFollowUps(String originalQuery, String aiResponse) {
        return chatClient.prompt()
                .system(promptSystemSpec -> promptSystemSpec.text(followupSystemPrompt))
                .user(promptUserSpec -> promptUserSpec.text(followupUserPrompt)
                        .param("originalQuery", originalQuery)
                        .param("aiResponse", aiResponse))
                .call()
                .entity(FollowUpResponse.class);
    }
}
