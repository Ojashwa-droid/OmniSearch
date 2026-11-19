package com.ojashwa.springai.omnisearch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class GroqTranscriptionService {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public GroqTranscriptionService(
            WebClient.Builder webClientBuilder,
            @Value("${omnisearch.groq.api-url}") String apiUrl,
            @Value("${omnisearch.groq.api-key:}") String apiKey,
            @Value("${omnisearch.groq.model:whisper-large-v3-turbo}") String model) {
        this.webClient = webClientBuilder.baseUrl(apiUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public Mono<String> transcribe(byte[] bytes, String filename, String mimeType) {
        if (!StringUtils.hasText(apiKey)) {
            log.error("Audio transcription blocked: GROQ_API_KEY is not configured");
            return Mono.error(new IllegalStateException("Audio transcription is not configured."));
        }
        long startedAt = System.nanoTime();
        log.info("Groq transcription request started: name={}, mimeType={}, sizeBytes={}, model={}",
                filename, mimeType, bytes.length, model);

        ByteArrayResource audio = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", audio)
                .filename(filename)
                .contentType(MediaType.parseMediaType(mimeType));
        multipart.part("model", model);
        multipart.part("response_format", "json");

        return webClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .retrieve()
                .bodyToMono(TranscriptionResponse.class)
                .timeout(Duration.ofMinutes(3))
                .map(response -> response.text() == null ? "" : response.text().trim())
                .flatMap(text -> StringUtils.hasText(text)
                        ? Mono.just(text)
                        : Mono.error(new IllegalArgumentException("No speech could be transcribed from " + filename + ".")))
                .doOnSuccess(text -> log.info("Groq transcription request completed: name={}, durationMs={}, transcriptCharacters={}",
                        filename, elapsedMillis(startedAt), text == null ? 0 : text.length()))
                .doOnError(error -> log.warn("Groq transcription request failed: name={}, durationMs={}, reason={}",
                        filename, elapsedMillis(startedAt), safeMessage(error)));
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message : error.getClass().getSimpleName();
    }

    private record TranscriptionResponse(String text) { }
}
