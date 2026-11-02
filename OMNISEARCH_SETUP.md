# Omnisearch - Perplexity AI Clone Setup Guide

This project implements a Perplexity AI backend clone using Spring AI, featuring web search, context engineering, streaming responses, and parallel follow-up question generation.

## Architecture Overview

```
User Query
    ↓
[Web Search Service] → Tavily API
    ↓
[Context Engineering Agent] → Processes search results
    ↓
[Response Generation Agent] → Generates answer (streaming) (parallel)
    ↓
[Follow-up Questions Agent] → Generates related questions (parallel)
    ↓
Supabase (stores search history)
```

## Prerequisites

1. **Java 21** installed
2. **Ollama** running locally with `llama3.2:3b` and `mxbai-embed-large` models
3. **Tavily API Key** - Get one at https://tavily.com/
4. **Supabase Project** - Create one at https://supabase.com/

## Setup Steps

### 1. Install Ollama Models

```bash
# Pull the required models
ollama pull llama3.2:3b
ollama pull mxbai-embed-large

# Start Ollama server
ollama serve
```

### 2. Get Tavily API Key

1. Sign up at https://tavily.com/
2. Get your API key from the dashboard
3. Note: Free tier includes 1000 requests/month

### 3. Set Up Supabase

1. Create a new project at https://supabase.com/
2. Go to Settings → API to get your URL and anon key
3. Create a table named `search_history` with the following schema:

```sql
CREATE TABLE search_history (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    query TEXT NOT NULL,
    answer TEXT,
    sources JSONB,
    follow_up_questions JSONB,
    source_count INTEGER,
    processing_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### 4. Configure Application Properties

Update `src/main/resources/application.properties`:

```properties
# Omnisearch Configuration
omnisearch.tavily.api-key=YOUR_TAVILY_API_KEY_HERE
omnisearch.tavily.api-url=https://api.tavily.com/search

# Supabase Configuration
supabase.url=https://YOUR_PROJECT.supabase.co
supabase.key=YOUR_SUPABASE_ANON_KEY
supabase.jdbc.url=jdbc:postgresql://YOUR_PROJECT.postgres.supabase.co:5432/postgres
```

### 5. Build and Run

```bash
# Build the project
./mvnw clean install

# Run the application
./mvnw spring-boot:run
```

## API Endpoints

### Search (Non-streaming)

```bash
POST /api/omnisearch/search
Content-Type: application/json

{
  "query": "What is the latest news about AI?",
  "saveHistory": true
}
```

Response:
```json
{
  "query": "What is the latest news about AI?",
  "answer": "Based on the search results...",
  "sources": ["[Source Title](URL)", ...],
  "followUpQuestions": ["Question 1", "Question 2", ...],
  "sourceCount": 10,
  "processingTimeMs": 3500
}
```

### Search (Streaming)

```bash
POST /api/omnisearch/search/stream
Content-Type: application/json

{
  "query": "Explain quantum computing",
  "saveHistory": true
}
```

Response: Server-Sent Events (SSE) stream of response chunks

### Health Check

```bash
GET /api/omnisearch/health
```

## Key Components

### Services

- **WebSearchService**: Handles Tavily API integration for web search
- **OmnisearchOrchestrator**: Coordinates all agents and manages parallel execution
- **SupabaseService**: Handles data persistence to Supabase

### Agents

- **ContextEngineeringAgent**: Processes search results and extracts relevant information
- **ResponseGenerationAgent**: Generates AI responses with streaming support
- **FollowUpQuestionsAgent**: Generates relevant follow-up questions in parallel

## Features

1. **Web Search Integration**: Uses Tavily API for comprehensive web search
2. **Context Engineering**: AI-powered processing of search results
3. **Streaming Responses**: Real-time response generation using WebFlux
4. **Parallel Execution**: Follow-up questions generated in parallel with response
5. **Source Citation**: Automatic source citation in responses
6. **Search History**: Stores searches in Supabase for analytics
7. **Supabase Auth Ready**: Configured for Supabase authentication integration

## Customization

### Change Web Search Provider

To use a different provider (e.g., Serper, Bing), modify `WebSearchService.java` and update the models accordingly.

### Adjust Model Parameters

Modify the temperature and other options in each agent's constructor:
- ContextEngineeringAgent: temperature(0.3) - more deterministic
- ResponseGenerationAgent: temperature(0.7) - more creative
- FollowUpQuestionsAgent: temperature(0.5) - balanced

### Add Authentication

1. Set up Supabase Auth
2. Add JWT validation middleware
3. Update SupabaseService to use authenticated user IDs

## Troubleshooting

### Ollama Connection Issues

Ensure Ollama is running:
```bash
ollama list
```

Check the base URL in application.properties matches your Ollama setup.

### Tavily API Errors

Verify your API key is correct and you haven't exceeded rate limits.

### Supabase Connection Issues

Check your Supabase URL and key are correct. Ensure the `search_history` table exists.

## Next Steps

1. Implement user authentication with Supabase Auth
2. Add user-specific search history retrieval
3. Implement search analytics dashboard
4. Add caching for frequently asked questions
5. Implement rate limiting
6. Add more sophisticated context engineering (e.g., RAG with vector store)
