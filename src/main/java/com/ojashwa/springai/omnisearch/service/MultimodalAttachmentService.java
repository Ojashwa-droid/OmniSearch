package com.ojashwa.springai.omnisearch.service;

import com.ojashwa.springai.omnisearch.model.AttachmentEvidence;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class MultimodalAttachmentService {

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm");
    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
            "audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/ogg",
            "application/ogg", "audio/wav", "audio/x-wav", "audio/webm", "video/webm", "video/mp4");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "md", "markdown", "html", "htm", "csv");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "html", "htm", "csv");

    private final GroqTranscriptionService transcriptionService;
    private final ExecutorService tikaExecutor;
    private final Tika tika = new Tika();
    private final int maxFiles;
    private final long maxFileBytes;
    private final long maxRequestBytes;
    private final int maxDocumentCharacters;
    private final int maxTotalCharacters;
    private final Duration tikaTimeout;

    public MultimodalAttachmentService(
            GroqTranscriptionService transcriptionService,
            @Qualifier("tikaExecutor") ExecutorService tikaExecutor,
            @Value("${omnisearch.upload.max-files:5}") int maxFiles,
            @Value("${omnisearch.upload.max-file-size:20MB}") org.springframework.util.unit.DataSize maxFileSize,
            @Value("${omnisearch.upload.max-request-size:40MB}") org.springframework.util.unit.DataSize maxRequestSize,
            @Value("${omnisearch.upload.max-document-characters:40000}") int maxDocumentCharacters,
            @Value("${omnisearch.upload.max-total-characters:100000}") int maxTotalCharacters,
            @Value("${omnisearch.upload.tika-timeout:30s}") Duration tikaTimeout) {
        this.transcriptionService = transcriptionService;
        this.tikaExecutor = tikaExecutor;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileSize.toBytes();
        this.maxRequestBytes = maxRequestSize.toBytes();
        this.maxDocumentCharacters = maxDocumentCharacters;
        this.maxTotalCharacters = maxTotalCharacters;
        this.tikaTimeout = tikaTimeout;
    }

    public Mono<List<AttachmentEvidence>> process(List<MultipartFile> files) {
        try {
            validateRequest(files);
        } catch (RuntimeException error) {
            log.warn("Attachment validation failed: attachmentCount={}, errorType={}, reason={}",
                    files == null ? 0 : files.size(), error.getClass().getSimpleName(), safeMessage(error));
            throw error;
        }
        long requestBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        log.info("Multimodal ingestion started: attachmentCount={}, totalBytes={}", files.size(), requestBytes);
        return Flux.fromIterable(files)
                .concatMap(file -> Mono.fromCallable(() -> readAndInspect(file))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(this::extractOrTranscribe))
                .collectList()
                .map(this::limitCombinedText)
                .doOnSuccess(items -> log.info("Multimodal ingestion completed: attachmentCount={}, textCharacters={}",
                        items.size(), items.stream().mapToInt(item -> item.extractedText() == null ? 0 : item.extractedText().length()).sum()))
                .doOnError(error -> log.warn("Multimodal ingestion failed: attachmentCount={}, reason={}", files.size(), safeMessage(error)));
    }

    private void validateRequest(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Attach at least one file.");
        if (files.size() > maxFiles) throw new IllegalArgumentException("A maximum of " + maxFiles + " attachments is allowed.");
        long total = 0;
        for (MultipartFile file : files) {
            String name = safeName(file.getOriginalFilename());
            if (file.isEmpty()) throw new IllegalArgumentException(name + " is empty.");
            if (file.getSize() > maxFileBytes) throw new IllegalArgumentException(name + " exceeds the per-file upload limit.");
            total += file.getSize();
        }
        if (total > maxRequestBytes) throw new IllegalArgumentException("The combined attachments exceed the request upload limit.");
        log.info("Attachment validation passed: attachmentCount={}, totalBytes={}", files.size(), total);
    }

    private PendingAttachment readAndInspect(MultipartFile file) throws Exception {
        String filename = safeName(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        String detected = tika.detect(bytes).toLowerCase(Locale.ROOT);
        String extension = extension(filename);

        if (IMAGE_MIME_TYPES.contains(detected)) {
            log.info("Attachment classified: name={}, kind=image, mimeType={}, sizeBytes={}", filename, detected, bytes.length);
            return new PendingAttachment(filename, "image", detected, bytes);
        }
        // Tika commonly identifies MediaRecorder's audio-only .webm output as Matroska.
        // Restrict this alias to .webm filenames, then send it to Groq as audio/webm.
        if (AUDIO_EXTENSIONS.contains(extension)
                && (AUDIO_MIME_TYPES.contains(detected)
                || (extension.equals("webm") && detected.equals("application/x-matroska")))) {
            String normalizedMime = normalizeAudioMime(detected, extension);
            log.info("Attachment classified: name={}, kind=audio, mimeType={}, sizeBytes={}", filename, normalizedMime, bytes.length);
            return new PendingAttachment(filename, "audio", normalizedMime, bytes);
        }
        if (DOCUMENT_EXTENSIONS.contains(extension) && isSupportedDocument(extension, detected)) {
            String normalizedMime = documentMime(extension, detected);
            log.info("Attachment classified: name={}, kind=document, mimeType={}, sizeBytes={}", filename, normalizedMime, bytes.length);
            return new PendingAttachment(filename, "document", normalizedMime, bytes);
        }
        log.warn("Attachment rejected: name={}, detectedMimeType={}, sizeBytes={}", filename, detected, bytes.length);
        throw new IllegalArgumentException(filename + " has an unsupported or mismatched file type (detected " + detected + ").");
    }

    private Mono<AttachmentEvidence> extractOrTranscribe(PendingAttachment attachment) {
        if ("image".equals(attachment.kind())) {
            log.info("Image ready for multimodal processing: name={}, sizeBytes={}", attachment.filename(), attachment.bytes().length);
            return Mono.just(new AttachmentEvidence(attachment.filename(), attachment.kind(), attachment.mimeType(), "", attachment.bytes()));
        }
        if ("audio".equals(attachment.kind())) {
            log.info("Audio transcription stage started: name={}, sizeBytes={}", attachment.filename(), attachment.bytes().length);
            return transcriptionService.transcribe(attachment.bytes(), attachment.filename(), attachment.mimeType())
                    .map(text -> {
                        log.info("Audio transcription stage completed: name={}, transcriptCharacters={}", attachment.filename(), text.length());
                        return new AttachmentEvidence(attachment.filename(), attachment.kind(), attachment.mimeType(), text, null);
                    })
                    .onErrorMap(error -> error instanceof IllegalArgumentException
                            ? error
                            : new IllegalStateException("Could not transcribe " + attachment.filename() + ": " + safeMessage(error), error));
        }
        log.info("Document extraction stage started: name={}, mimeType={}, sizeBytes={}",
                attachment.filename(), attachment.mimeType(), attachment.bytes().length);
        return Mono.fromCallable(() -> {
                    String text = parseWithTimeout(attachment);
                    if (!StringUtils.hasText(text)) throw new IllegalArgumentException("No readable text was found in " + attachment.filename() + ".");
                    text = text.length() > maxDocumentCharacters ? text.substring(0, maxDocumentCharacters) : text;
                    log.info("Document extraction stage completed: name={}, extractedCharacters={}", attachment.filename(), text.length());
                    return new AttachmentEvidence(attachment.filename(), attachment.kind(), attachment.mimeType(), text, "application/pdf".equals(attachment.mimeType()) ? attachment.bytes() : null);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> new IllegalArgumentException("Could not read " + attachment.filename() + ": " + safeMessage(error), error));
    }

    private String parseWithTimeout(PendingAttachment attachment) throws Exception {
        long startedAt = System.nanoTime();
        Future<String> parsing = tikaExecutor.submit(() -> tika.parseToString(new ByteArrayInputStream(attachment.bytes())));
        try {
            String result = parsing.get(tikaTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Tika parser completed: name={}, durationMs={}, extractedCharacters={}",
                    attachment.filename(), elapsedMillis(startedAt), result.length());
            return result;
        } catch (TimeoutException error) {
            parsing.cancel(true);
            log.warn("Tika parser timed out: name={}, timeoutSeconds={}", attachment.filename(), tikaTimeout.toSeconds());
            throw new IllegalArgumentException("document parsing exceeded " + tikaTimeout.toSeconds() + " seconds", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
    }

    private List<AttachmentEvidence> limitCombinedText(List<AttachmentEvidence> evidence) {
        int remaining = maxTotalCharacters;
        List<AttachmentEvidence> bounded = new ArrayList<>(evidence.size());
        for (AttachmentEvidence item : evidence) {
            String text = item.extractedText() == null ? "" : item.extractedText();
            if (text.length() > remaining) text = text.substring(0, Math.max(0, remaining));
            remaining -= text.length();
            bounded.add(new AttachmentEvidence(item.filename(), item.kind(), item.mimeType(), text, item.mediaBytes()));
        }
        return bounded;
    }

    private static boolean isSupportedDocument(String extension, String mime) {
        if (TEXT_EXTENSIONS.contains(extension)) return mime.startsWith("text/") || mime.equals("application/csv");
        return switch (extension) {
            case "pdf" -> mime.equals("application/pdf");
            case "doc" -> mime.equals("application/msword");
            case "docx" -> mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "ppt" -> mime.equals("application/vnd.ms-powerpoint");
            case "pptx" -> mime.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "xls" -> mime.equals("application/vnd.ms-excel");
            case "xlsx" -> mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> false;
        };
    }

    private static String documentMime(String extension, String detected) {
        if (extension.equals("csv") && detected.equals("text/plain")) return "text/csv";
        if (extension.equals("md") || extension.equals("markdown")) return "text/markdown";
        if (extension.equals("htm")) return "text/html";
        return detected;
    }

    private static String normalizeAudioMime(String detected, String extension) {
        return switch (extension) {
            case "mp3", "mpeg", "mpga" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            case "webm" -> "audio/webm";
            case "mp4" -> "video/mp4";
            default -> detected;
        };
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String safeName(String filename) {
        if (!StringUtils.hasText(filename)) return "attachment";
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_");
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message : "provider or parser error";
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record PendingAttachment(String filename, String kind, String mimeType, byte[] bytes) { }
}
