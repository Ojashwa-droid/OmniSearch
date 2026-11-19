package com.ojashwa.springai.omnisearch.model;

public record AttachmentEvidence(
        String filename,
        String kind,
        String mimeType,
        String extractedText,
        byte[] mediaBytes) {

    public boolean hasMedia() {
        return mediaBytes != null && mediaBytes.length > 0;
    }
}
