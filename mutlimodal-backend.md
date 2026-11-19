# OmniSearch multimodal backend

## Summary

Keep text-only questions on the existing `POST /api/omnisearch/conversation/stream` route. Add a multipart route for image, document, and audio inputs; it will prepare evidence, derive web searches, stream a grounded answer in the existing format, and leave the existing follow-up flow intact.

I found the plan at the repository root as `multimodal-input-plan.md`, rather than under `docs/`. Gemini supports image and PDF input through its API; use Tika to extract text from Office and other supported document formats. [Google Gemini document processing](https://ai.google.dev/gemini-api/docs/document-processing), [Spring AI Google GenAI chat](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html)

## Implementation changes

- **Shared multipart foundation:** Replace the incomplete multimodal controller stub with a `multipart/form-data` endpoint accepting `query`, `conversationId`, and repeated `attachments`. Keep the current JSON endpoint unchanged. Enforce the plan’s five-file, per-file, and total-request limits; verify actual file types, return clear per-file errors, and keep uploads temporary. Move Tavily and Groq credentials to environment configuration.
- **Image slice:** First implement image validation and Gemini Flash-Lite multimodal query planning. The planner will use the question and image content to produce up to three concise search queries. Search results and the original image then go to the response generator, which streams through the existing SSE format.
- **Document slice:** Use the existing Tika dependency for PDF, Office, and text formats. Give extracted text to the planner and response generator. For PDFs, provide both extracted text and the original PDF to Gemini when within the provider’s limits; do not assume other document formats can be sent directly to Gemini.
- **Audio slice:** Send audio to Groq’s transcription endpoint using `whisper-large-v3-turbo`; pass the transcript into planning and answer generation, and discard the audio bytes after processing. The model’s accepted formats and Groq’s upload limit are documented by [Groq speech-to-text](https://console.groq.com/docs/speech-to-text).
- **Mixed inputs and conversation:** Combine all usable question and attachment evidence into one plan and one answer, whether the request has one modality or several. Keep original media out of chat memory; retain the question, extracted text or transcript, attachment names, and completed answer. Preserve the selected `conversationId` for follow-up turns.
- **Search and streaming:** Run planned Tavily searches with bounded concurrency, deduplicate by canonical URL, and cap the merged results at ten per turn. Assign citation numbers after merging so the leading `SOURCES` payload and answer citations agree. Keep that payload before answer text for the existing frontend parser. The frontend already calls the follow-ups endpoint after a completed stream, so no follow-up API change is planned.
- **Failure behavior:** Reject invalid, unsupported, oversized, or unreadable uploads with a clear error. If planning fails and the user supplied text, fall back to searching that text; if there is no usable text or transcript, explain what is missing. If some searches fail, use successful results; if all fail, return a clear search error and never present synthetic fallback URLs as real citations. Run Tika work off the reactive event loop with bounded execution and timeouts.

## Verification

- Confirm the project builds after replacing the incomplete controller stub.
- Exercise text-only requests to confirm the existing route and stream remain unchanged.
- Exercise image, PDF, Office/text, audio, and mixed requests; verify limits, invalid uploads, empty/noisy transcripts, provider failures, and cancellation.
- Verify source numbering matches citations, the sources payload arrives before streamed answer text, follow-ups use the same conversation ID, and no media bytes enter persistent chat memory.

## Assumptions

- Use **Gemini Flash-Lite** for query planning and answer generation, and **Groq Whisper** for transcription.
- Implement shared multipart validation first, then **image → documents → audio**, as selected.
- Keep binaries temporary and chat memory sanitized; backend persistence of uploaded media is out of scope.
