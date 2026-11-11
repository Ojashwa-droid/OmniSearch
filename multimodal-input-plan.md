# OmniSearch multimodal research pipeline

## Summary

Add image, document, and audio inputs to the existing text research flow. 
Process each attachment according to its type, use Gemini to derive bounded web searches from the question 
and attachment evidence, retrieve real web sources, then stream a grounded answer using both the retrieved
sources and the original inputs.

Keep the current text-only API working. The first version will process uploads during the request but won’t save 
the original files to a backend database. The suggested plan document path is `docs/multimodal-input-plan.md`.

## Recommended design and data flow

1. **Accept multipart uploads.** Add `POST /api/omnisearch/conversation/stream/multimodal` with `multipart/form-data` fields `query`, `conversationId`, and repeated `attachments`. Keep the current JSON streaming endpoint unchanged for existing text-only clients. The frontend uses the new endpoint when a chat includes attachments.

2. **Validate and classify before processing.** Allow up to five files per request and use configurable limits, starting at 20 MiB per file and 40 MiB per request. Detect the actual MIME type from the bytes rather than trusting the filename or browser-provided content type. Initially allow common images (JPEG, PNG, WebP, HEIC/HEIF), audio formats supported by Groq Whisper, and common documents (PDF, DOC/DOCX, PPT/PPTX, XLS/XLSX, TXT, Markdown, HTML, CSV). Reject unsupported or malformed files with a filename-specific error. Spring Boot multipart limits should be configured explicitly; its defaults are 1 MiB per file and 10 MiB per request. [Spring Boot multipart configuration](https://docs.spring.io/spring-boot/3.4/how-to/spring-mvc.html)

3. **Create normalized attachment context.**
    - **Images:** Keep the original image bytes for Gemini vision. Don’t reduce the image to a caption alone.
    - **Documents:** Use the project’s existing `spring-ai-tika-document-reader` dependency to extract text and metadata. For PDFs within the configured Gemini media budget, send both the Tika extraction and the PDF itself so the model can also inspect charts, tables, and layout. Tika extracts text across many formats; it does not guarantee faithful visual or table reconstruction. [Spring AI TikaDocumentReader](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/reader/tika/TikaDocumentReader.html), [Gemini document understanding](https://ai.google.dev/gemini-api/docs/document-processing?authuser=4&hl=en)
    - **Audio:** Send the audio file to Groq’s OpenAI-compatible `/audio/transcriptions` endpoint using `whisper-large-v3-turbo`. Use the returned transcript as text evidence; keep timestamps if returned, but don’t require diarization in v1. Configure the application audio limit to 20 MiB, below Groq’s documented 25 MB free-tier upload limit, and make it adjustable for the account tier. [Groq speech-to-text documentation](https://console.groq.com/docs/speech-to-text)

   Keep extracted text bounded: start with a 40,000-character limit per document and 100,000 characters across all attachments. Run Tika parsing on a bounded worker pool rather than the WebFlux event loop, and apply parser time limits. Tika documents configurable timeout and robustness controls. [Apache Tika timeout guidance](https://tika.apache.org/docs/4.2.x/pipes/timeouts.html)

4. **Plan searches, then retrieve sources.** Add a `SearchQueryPlanner` that receives the original question, document text, audio transcript, and image/PDF media. It returns a structured plan with one to three concise web queries. Validate the result before sending it to Tavily. Run those searches with bounded concurrency, deduplicate results by canonical URL, rank them, and cap the combined retrieved context. Gemini 3.5 Flash-Lite is documented as accepting text, image, video, audio, and PDF inputs; Spring AI exposes multimodal inputs through `UserMessage` media. [Gemini model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite?authuser=4), [Spring AI multimodality](https://docs.spring.io/spring-ai/reference/api/multimodality.html)

5. **Stream the grounded answer.** Extend `SearchContext` to carry the user’s original question, generated queries, retrieved web context, normalized text/transcripts, attachment labels, and supported media for the current turn. Keep the existing numbered web-source cards and citation format. The frontend should show the attached filenames and distinguish processing steps such as “Reading files” and “Transcribing audio.” Web citations remain numbered links; when referencing uploaded evidence, the answer should name the attachment rather than invent a URL.

## Backend and frontend changes

- Add an attachment ingestion service, a Groq transcription service configured through `GROQ_API_KEY`, a Gemini search-planning agent, and a multimodal response path. Reuse the existing Tavily search service and response stream where practical.
- Update the composer to select and remove multiple files, show attachment names and image thumbnails, and submit multipart data. Preserve Stop, network error, and per-chat source behavior.
- Keep original upload bytes temporary and out of chat memory. For the multimodal memory exchange, store the user’s question, extracted text/transcript, attachment names, and final answer—not image or file bytes. The existing `MessageChatMemoryAdvisor` may need a sanitized/custom memory path for multimodal turns so raw media is not retained in its in-memory conversation history.
- Move provider credentials out of checked-in configuration and into environment or deployment secrets before adding Groq credentials. Do not log file contents or transcription text.
- Remove or clearly mark the current Tavily synthetic fallback. A simulated result must never be presented as a real web citation. If live search fails, report that clearly and either answer from attachments with an explicit limitation or return a search error.
- Treat uploaded content and web results as untrusted evidence, not instructions. Keep the planner limited to returning search queries; validate its output and give it no privileged tools. Prompt injection in retrieved or uploaded content is a documented risk. [Gemini safety guidance](https://ai.google.dev/gemini-api/docs/safety-guidance?hl=en), [OWASP GenAI prompt injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

## Failure behavior and acceptance checks

- Invalid type, oversize file, empty/malformed upload, Tika failure, Groq failure, and Gemini planner failure produce clear, specific status/error messages; do not silently ignore a selected attachment. If the planner fails but a text question exists, fall back to searching that question. If no usable question or transcript can be produced, stop with a clear error.
- Test text-only compatibility, each modality alone, mixed attachments, image questions that require visual analysis, scanned or text-based PDFs, empty and noisy audio, unsupported MIME types, size boundaries, parser timeouts, provider rate limits/timeouts, Tavily failure, cancellation, and successful streamed citations.
- Verify that citations map to the deduplicated web-source list, attachment labels remain visible per turn, and raw media bytes are not added to chat memory.
- Keep database persistence and durable file storage out of this phase. Later, backend chat APIs can persist turn text, transcripts, sources, and attachment metadata, with binaries stored separately in object storage.

## Assumptions and defaults

- “Grok Wisher” means **Groq Whisper**; you confirmed Groq Whisper for v1.
- Gemini 3.5 Flash-Lite remains the response and planning model, subject to a small integration check against the repository’s Spring AI 1.1.0 dependency before coding the media path.
- Users may submit a question with attachments or attachments alone; in the latter case, the planner derives the search intent from the content.
- Upload binaries are request-scoped and are not retained after processing. Existing browser chat history may keep transcript and attachment metadata, but durable multimodal history is deferred until backend persistence is ready.
