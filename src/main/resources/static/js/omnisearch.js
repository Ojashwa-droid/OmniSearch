'use strict';

const API_BASE = '/api/omnisearch';
const ACTIVE_CHAT_KEY = 'omnisearch.activeChatId';
const LEGACY_CID_KEY = 'omnisearch.conversationId';
const CHAT_DB_NAME = 'omnisearch-browser-chats';
const CHAT_DB_VERSION = 1;
const CHAT_STORE_NAME = 'chats';
const form = document.getElementById('search-form');
const input = document.getElementById('query-input');
const sendBtn = document.getElementById('send-btn');
const stopBtn = document.getElementById('stop-btn');
const thread = document.getElementById('thread');
const home = document.getElementById('home');
const sessionSourcesToggle = document.getElementById('session-sources-toggle');
const sessionSourcesToolbar = document.getElementById('session-sources-toolbar');
const sessionSourcesCount = document.getElementById('session-sources-count');
const sessionSourcesPanel = document.getElementById('session-sources-panel');
const sessionSourcesClose = document.getElementById('session-sources-close');
const sessionSourcesSummary = document.getElementById('session-sources-summary');
const sessionSourcesList = document.getElementById('session-sources-list');
const chatSidebarToggle = document.getElementById('chat-sidebar-toggle');
const chatSidebar = document.getElementById('chat-sidebar');
const chatSidebarBackdrop = document.getElementById('chat-sidebar-backdrop');
const chatSidebarClose = document.getElementById('chat-sidebar-close');
const newChatButton = document.getElementById('new-chat-btn');
const chatHistoryList = document.getElementById('chat-history-list');
const chatStorageNotice = document.getElementById('chat-storage-notice');

let abortController = null;
let isStreaming = false;
let chatDatabase = null;
let chatRecords = [];
let activeChat = null;
let activeChatId = null;
let storagePersistent = true;
const saveTimers = new Map();
let saveQueue = Promise.resolve();

function getDomain(url) {
    try {
        return new URL(url).hostname.replace(/^www\./, '');
    } catch {
        return 'Source';
    }
}

function safeHttpUrl(value) {
    if (typeof value !== 'string' || !value.trim()) return null;
    try {
        const url = new URL(value.trim(), window.location.origin);
        return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
    } catch {
        return null;
    }
}

function createId() {
    return window.crypto?.randomUUID?.() || `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function openChatDatabase() {
    return new Promise((resolve, reject) => {
        if (!window.indexedDB) {
            reject(new Error('IndexedDB is unavailable in this browser.'));
            return;
        }
        const request = indexedDB.open(CHAT_DB_NAME, CHAT_DB_VERSION);
        request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains(CHAT_STORE_NAME)) {
                database.createObjectStore(CHAT_STORE_NAME, { keyPath: 'id' });
            }
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error || new Error('Could not open local chat history.'));
        request.onblocked = () => reject(new Error('Local chat history is blocked by another browser tab.'));
    });
}

function readAllChats() {
    return new Promise((resolve, reject) => {
        const request = chatDatabase.transaction(CHAT_STORE_NAME, 'readonly').objectStore(CHAT_STORE_NAME).getAll();
        request.onsuccess = () => resolve(request.result || []);
        request.onerror = () => reject(request.error || new Error('Could not read local chat history.'));
    });
}

function writeChat(chat) {
    return new Promise((resolve, reject) => {
        const transaction = chatDatabase.transaction(CHAT_STORE_NAME, 'readwrite');
        transaction.objectStore(CHAT_STORE_NAME).put(chat);
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error || new Error('Could not save this chat.'));
        transaction.onabort = () => reject(transaction.error || new Error('Saving this chat was interrupted.'));
    });
}

function setStorageWarning(message) {
    storagePersistent = false;
    if (!chatStorageNotice) return;
    chatStorageNotice.textContent = message;
    chatStorageNotice.hidden = false;
}

function readBrowserValue(key) {
    try { return localStorage.getItem(key); } catch { return null; }
}

function writeBrowserValue(key, value) {
    try { localStorage.setItem(key, value); } catch {
        setStorageWarning('This browser could not save the active chat selection.');
    }
}

function normaliseChatRecord(chat) {
    if (!chat || typeof chat.id !== 'string' || !Array.isArray(chat.turns)) return null;
    return {
        id: chat.id,
        title: String(chat.title || 'New chat'),
        createdAt: Number(chat.createdAt) || Date.now(),
        updatedAt: Number(chat.updatedAt) || Date.now(),
        turns: chat.turns.map((turn) => ({
            query: String(turn?.query || ''),
            answerMarkdown: String(turn?.answerMarkdown || ''),
            sources: Array.isArray(turn?.sources) ? turn.sources
                .filter((source) => Number.isInteger(source.sourceNumber) && source.sourceNumber > 0 && safeHttpUrl(source.url))
                .map((source) => ({ sourceNumber: source.sourceNumber, url: safeHttpUrl(source.url), title: String(source.title || '') })) : [],
            status: turn?.status === 'streaming' ? 'interrupted' : String(turn?.status || 'completed'),
            errorMessage: String(turn?.errorMessage || ''),
            followUps: Array.isArray(turn?.followUps) ? turn.followUps.map(String) : []
        }))
    };
}

function readLegacySources(conversationId) {
    try {
        const value = sessionStorage.getItem(`omnisearch.sources.${conversationId}`);
        const saved = JSON.parse(value || '{"turns":[]}');
        if (!Array.isArray(saved.turns)) return [];
        return saved.turns.map((turn) => ({
            query: String(turn.query || ''),
            answerMarkdown: '',
            sources: Array.isArray(turn.sources) ? turn.sources.map((source, index) => ({
                sourceNumber: Number(source.sourceNumber) || index + 1,
                url: safeHttpUrl(source.url),
                title: String(source.title || '')
            })).filter((source) => source.url) : [],
            status: 'legacy-unavailable',
            followUps: []
        })).filter((turn) => turn.sources.length);
    } catch {
        return [];
    }
}

function createBlankChat(id = createId()) {
    const now = Date.now();
    return { id, title: 'New chat', createdAt: now, updatedAt: now, turns: [] };
}

async function initialiseChatHistory() {
    try {
        chatDatabase = await openChatDatabase();
        chatRecords = (await readAllChats()).map(normaliseChatRecord).filter(Boolean);
    } catch (error) {
        console.warn('Local chat history is unavailable:', error);
        setStorageWarning('Chat history storage is unavailable. This chat will only remain until you close this page.');
        chatRecords = [];
    }

    const legacyId = (() => { try { return sessionStorage.getItem(LEGACY_CID_KEY); } catch { return null; } })();
    if (legacyId && !chatRecords.some((chat) => chat.id === legacyId)) {
        const turns = readLegacySources(legacyId);
        const migrated = createBlankChat(legacyId);
        migrated.turns = turns;
        if (turns.length) migrated.title = turns.find((turn) => turn.query)?.query || 'Previous chat';
        migrated.updatedAt = Date.now();
        chatRecords.push(migrated);
        if (chatDatabase) {
            try { await writeChat(migrated); } catch (error) {
                console.warn('Could not migrate the previous source list:', error);
                setStorageWarning('The previous source list could not be migrated to chat history.');
            }
        }
    }

    const requestedId = readBrowserValue(ACTIVE_CHAT_KEY) || legacyId;
    activeChat = chatRecords.find((chat) => chat.id === requestedId)
        || (requestedId ? createBlankChat(requestedId) : null)
        || [...chatRecords].sort((left, right) => right.updatedAt - left.updatedAt)[0]
        || createBlankChat();
    activeChatId = activeChat.id;
    writeBrowserValue(ACTIVE_CHAT_KEY, activeChatId);
    renderActiveChat();
    renderChatHistory();
}

function saveActiveChatSoon() {
    if (!activeChat) return;
    trackActiveChat();
    saveChatSoon(activeChat);
}

function saveChatSoon(chat) {
    if (!chatDatabase || !chat) return;
    window.clearTimeout(saveTimers.get(chat.id));
    const timer = window.setTimeout(() => {
        saveTimers.delete(chat.id);
        const snapshot = JSON.parse(JSON.stringify(chat));
        saveQueue = saveQueue.then(() => writeChat(snapshot)).catch((error) => {
            console.warn('Could not save chat history:', error);
            setStorageWarning('Could not save this chat. Check browser storage availability.');
        });
    }, 180);
    saveTimers.set(chat.id, timer);
}

function persistActiveChatNow() {
    if (!activeChat) return Promise.resolve();
    trackActiveChat();
    renderChatHistory();
    if (!chatDatabase) return Promise.resolve();
    window.clearTimeout(saveTimers.get(activeChat.id));
    saveTimers.delete(activeChat.id);
    const snapshot = JSON.parse(JSON.stringify(activeChat));
    saveQueue = saveQueue.then(() => writeChat(snapshot)).catch((error) => {
        console.warn('Could not save chat history:', error);
        setStorageWarning('Could not save this chat. Check browser storage availability.');
    });
    return saveQueue;
}

function trackActiveChat() {
    if (!activeChat) return;
    const index = chatRecords.findIndex((chat) => chat.id === activeChat.id);
    if (index >= 0) chatRecords[index] = activeChat;
    else chatRecords.push(activeChat);
}

function esc(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function normaliseCitations(markdown) {
    let formatted = markdown
        .replace(/\[\[(\d+)\]\]/g, '[$1]')
        // Treat a spaced asterisk wrapper as decoration, not emphasis syntax.
        .replace(/^([ \t]*(?:[-+*]|\d+[.)])\s+)\*\s+(.+?)\*\s*$/gm, '$1$2')
        // Remove accidental decorative markers left inside an already valid list item.
        .replace(/^([ \t]*(?:[-+*]|\d+[.)])\s+)(?:\*|o|•|◦|▪)\s+(?=\S)/gm, '$1')
        // Turn standalone visual bullets into valid Markdown list markers.
        .replace(/^([ \t]*)[•◦▪]\s+(?=\S)/gm, '$1- ')
        .replace(/^([ \t]*)o\s+(?=\S)/gm, '$1- ')
        // Some streamed model responses place Markdown block markers directly after text.
        .replace(/([^\n])\s*(#{1,6}\s+)/g, '$1\n\n$2')
        .replace(/([^\n:])\s*(\*\s{2,})(?=\S)/g, '$1\n\n- ')
        .replace(/(:)\s*(\*\s{2,})(?=\S)/g, '$1\n\n- ');

    // Recover a compact table if adjacent rows were streamed without line breaks.
    if (/\|\s*:?-{2,}/.test(formatted)) {
        formatted = formatted.replace(/\|\|\s*/g, '|\n|');
        formatted = formatted.replace(/(Summary Overview)\s*(\|)/i, '$1\n\n$2');
    }
    return formatted;
}

function sanitizeMarkdownHtml(html) {
    const allowed = new Set([
        'P', 'BR', 'HR', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
        'UL', 'OL', 'LI', 'BLOCKQUOTE', 'STRONG', 'EM', 'DEL',
        'CODE', 'PRE', 'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'A'
    ]);
    const documentHtml = new DOMParser().parseFromString(html, 'text/html');

    documentHtml.body.querySelectorAll('*').forEach((element) => {
        if (!allowed.has(element.tagName)) {
            element.replaceWith(documentHtml.createTextNode(element.textContent || ''));
            return;
        }

        if (element.tagName !== 'A') {
            [...element.attributes].forEach((attribute) => element.removeAttribute(attribute.name));
            return;
        }

        const href = safeHttpUrl(element.getAttribute('href') || '');
        if (!href) {
            element.replaceWith(documentHtml.createTextNode(element.textContent || ''));
            return;
        }
        const label = element.textContent.trim();
        element.removeAttribute('title');
        element.removeAttribute('target');
        element.removeAttribute('rel');
        [...element.attributes].forEach((attribute) => {
            if (attribute.name !== 'href') element.removeAttribute(attribute.name);
        });
        element.href = href;
        element.target = '_blank';
        element.rel = 'noopener noreferrer';
        if (/^\d+$/.test(label)) element.className = 'cite-pill';
    });

    return documentHtml.body.innerHTML;
}

function renderAnswer(element, markdown, withCursor = false) {
    const clean = normaliseCitations(markdown);
    let html;
    try {
        html = typeof marked !== 'undefined'
            ? sanitizeMarkdownHtml(marked.parse(clean, { gfm: true, breaks: true }))
            : `<p>${esc(clean).replace(/\n/g, '<br>')}</p>`;
    } catch (error) {
        console.error('Could not render answer markdown:', error);
        html = `<p>${esc(clean).replace(/\n/g, '<br>')}</p>`;
    }
    element.innerHTML = html;
    if (withCursor) {
        const cursor = document.createElement('span');
        cursor.className = 'stream-cursor';
        cursor.setAttribute('aria-hidden', 'true');
        element.appendChild(cursor);
    }
}

function makeClipping(source, index, delay) {
    if (!source || typeof source !== 'object') return null;
    const url = safeHttpUrl(source.url);
    if (!url) return null;

    const card = document.createElement('a');
    card.className = 'clipping';
    card.href = url;
    card.target = '_blank';
    card.rel = 'noopener noreferrer';
    card.style.animationDelay = `${delay}s`;
    card.setAttribute('aria-label', `Source ${index}: ${source.title || getDomain(url)}`);

    const number = document.createElement('span');
    number.className = 'clip-num';
    number.textContent = String(index).padStart(2, '0');

    const metadata = document.createElement('span');
    metadata.className = 'clip-top';
    const favicon = document.createElement('img');
    favicon.className = 'clip-fav';
    favicon.src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(new URL(url).hostname)}&sz=32`;
    favicon.alt = '';
    favicon.loading = 'lazy';
    favicon.onerror = () => { favicon.hidden = true; };
    metadata.appendChild(favicon);

    const domain = document.createElement('span');
    domain.className = 'clip-domain';
    domain.textContent = getDomain(url);
    metadata.appendChild(domain);

    const title = document.createElement('span');
    title.className = 'clip-title';
    title.textContent = source.title || getDomain(url);

    card.append(number, metadata, title);
    return card;
}

function renderClippings(container, sources) {
    if (!Array.isArray(sources)) return;
    sources.forEach((source, index) => {
        const card = makeClipping(source, index + 1, index * 0.04);
        if (card) container.appendChild(card);
    });
}

function canonicalSourceUrl(value) {
    const safeUrl = safeHttpUrl(value);
    if (!safeUrl) return null;
    try {
        const url = new URL(safeUrl);
        url.hash = '';
        return url.href;
    } catch {
        return null;
    }
}

function addSourcesToSession(query, turnNumber, sources) {
    if (!Array.isArray(sources)) return;
    const collected = sources
        .map((source, index) => ({
            sourceNumber: index + 1,
            url: safeHttpUrl(source?.url),
            title: String(source?.title || '')
        }))
        .filter((source) => source.url);
    if (!collected.length) return;
    const turn = activeChat?.turns[turnNumber - 1];
    if (!turn) return;
    turn.query = query;
    turn.sources = collected;
    turn.status = 'streaming';
    activeChat.updatedAt = Date.now();
    saveActiveChatSoon();
    renderSessionSources();
}

function makeSessionSourceCard(source, references) {
    const card = document.createElement('article');
    card.className = 'session-source-card';

    const link = document.createElement('a');
    link.className = 'session-source-item';
    link.href = source.url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';

    const number = document.createElement('span');
    number.className = 'session-source-number';
    number.textContent = `#${String(source.primarySourceNumber).padStart(2, '0')}`;

    const favicon = document.createElement('img');
    favicon.className = 'session-source-favicon';
    favicon.src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(new URL(source.url).hostname)}&sz=32`;
    favicon.alt = '';
    favicon.loading = 'lazy';
    favicon.referrerPolicy = 'no-referrer';
    favicon.onerror = () => { favicon.hidden = true; };

    const details = document.createElement('span');
    const domain = document.createElement('span');
    domain.className = 'session-source-domain';
    domain.textContent = getDomain(source.url);
    const title = document.createElement('span');
    title.className = 'session-source-title';
    title.textContent = source.title || getDomain(source.url);
    details.append(domain, title);
    link.append(number, favicon, details);
    card.appendChild(link);

    if (references.length > 1) {
        const uses = document.createElement('div');
        uses.className = 'session-source-uses';
        references.forEach((reference) => {
            const tag = document.createElement('span');
            tag.className = 'session-source-use';
            tag.textContent = `Q${reference.turnNumber} · #${reference.sourceNumber}`;
            tag.title = reference.query;
            uses.appendChild(tag);
        });
        card.appendChild(uses);
    }

    return card;
}

function renderSessionSources() {
    if (!sessionSourcesList || !sessionSourcesToggle) return;

    const turns = (activeChat?.turns || []).map((turn, index) => ({
        ...turn,
        turnNumber: index + 1,
        sources: Array.isArray(turn.sources) ? turn.sources : []
    })).filter((turn) => turn.sources.length);
    const uniqueSources = new Map();

    turns.forEach((turn) => {
        [...turn.sources]
            .sort((left, right) => left.sourceNumber - right.sourceNumber)
            .forEach((source) => {
                const key = canonicalSourceUrl(source.url);
                if (!key) return;
                let item = uniqueSources.get(key);
                if (!item) {
                    item = {
                        url: safeHttpUrl(source.url),
                        title: source.title,
                        latestTurnNumber: turn.turnNumber,
                        primarySourceNumber: source.sourceNumber,
                        references: []
                    };
                    uniqueSources.set(key, item);
                } else if (!item.title && source.title) {
                    item.title = source.title;
                }
                item.references.push({
                    turnNumber: turn.turnNumber,
                    sourceNumber: source.sourceNumber,
                    query: turn.query
                });
            });
    });

    const entries = [...uniqueSources.values()];
    if (sessionSourcesToolbar) sessionSourcesToolbar.hidden = entries.length === 0;
    sessionSourcesToggle.hidden = entries.length === 0;
    sessionSourcesCount.textContent = String(entries.length);
    sessionSourcesToggle.setAttribute('aria-label', `View ${entries.length} unique sources used in this chat`);
    sessionSourcesSummary.textContent = `${entries.length} unique source${entries.length === 1 ? '' : 's'} across ${turns.length} question${turns.length === 1 ? '' : 's'}`;
    sessionSourcesList.replaceChildren();

    turns.forEach((turn) => {
        const items = entries.filter((entry) => entry.latestTurnNumber === turn.turnNumber);
        const group = document.createElement('section');
        group.className = 'session-source-group';
        const heading = document.createElement('h3');
        heading.className = 'session-source-group-title';
        heading.textContent = `Q${turn.turnNumber} · ${turn.query}`;
        heading.title = turn.query;
        group.appendChild(heading);

        if (items.length) {
            items.forEach((item) => group.appendChild(makeSessionSourceCard(item, item.references)));
        } else {
            const reused = document.createElement('p');
            reused.className = 'session-sources-reused';
            reused.textContent = 'This question reused sources listed above.';
            group.appendChild(reused);
        }
        sessionSourcesList.appendChild(group);
    });
}

function getNextSessionTurnNumber() { return (activeChat?.turns.length || 0) + 1; }

function closeSessionSources(restoreFocus = false) {
    if (!sessionSourcesPanel || !sessionSourcesToggle) return;
    sessionSourcesPanel.hidden = true;
    sessionSourcesToggle.setAttribute('aria-expanded', 'false');
    if (restoreFocus) sessionSourcesToggle.focus();
}

function extractSourcesHeader(text) {
    const match = text.match(/<!--SOURCES:([\s\S]*?)-->/);
    if (!match) return null;
    try {
        return { sources: JSON.parse(match[1]), comment: match[0] };
    } catch {
        return null;
    }
}

function setStreaming(on) {
    isStreaming = on;
    sendBtn.disabled = on;
    sendBtn.hidden = on;
    stopBtn.hidden = !on;
    input.disabled = on;
    if (!on) input.focus();
}

function addActionButton(container, label, action) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'action-btn';
    button.textContent = label;
    button.addEventListener('click', action);
    container.appendChild(button);
    return button;
}

function addCopyButton(answer, actions) {
    const button = addActionButton(actions, 'Copy', async () => {
        try {
            await navigator.clipboard.writeText(answer);
            button.textContent = 'Copied';
            button.classList.add('copied');
            window.setTimeout(() => {
                button.textContent = 'Copy';
                button.classList.remove('copied');
            }, 1800);
        } catch {
            button.textContent = 'Copy unavailable';
        }
    });
    button.setAttribute('aria-label', 'Copy answer');
}

function renderFollowUps(slot, questions) {
    const section = document.createElement('section');
    section.className = 'also-asked';
    const label = document.createElement('p');
    label.className = 'also-label';
    label.textContent = 'Also asked';
    section.appendChild(label);
    questions.forEach((question) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'also-row';
        const text = document.createElement('span');
        text.textContent = question;
        const arrow = document.createElement('span');
        arrow.className = 'also-arrow';
        arrow.setAttribute('aria-hidden', 'true');
        arrow.textContent = '→';
        button.append(text, arrow);
        button.addEventListener('click', () => {
            input.value = question;
            submitQuery();
        });
        section.appendChild(button);
    });
    slot.replaceChildren(section);
}

async function loadFollowUps(query, answer, slot, turnRecord, chatRecord) {
    const section = document.createElement('section');
    section.className = 'also-asked';
    section.setAttribute('aria-live', 'polite');
    const label = document.createElement('p');
    label.className = 'also-label';
    label.textContent = 'Also asked';
    const status = document.createElement('p');
    status.className = 'followup-status';
    status.textContent = 'Finding related questions…';
    section.append(label, status);
    slot.appendChild(section);

    try {
        const response = await fetch(`${API_BASE}/followups`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ originalQuery: query, answer })
        });
        if (!response.ok) throw new Error(`Server returned ${response.status}`);
        const data = await response.json();
        const questions = Array.isArray(data.followUpQuestions) ? data.followUpQuestions : [];
        status.remove();
        if (!questions.length) {
            status.textContent = 'No related questions available.';
            section.appendChild(status);
            return;
        }

        turnRecord.followUps = questions;
        saveChatSoon(chatRecord);
        renderFollowUps(slot, questions);
    } catch (error) {
        status.textContent = 'Related questions are unavailable right now.';
        console.warn('Follow-ups unavailable:', error);
    }
}

function eventPayload(block) {
    const data = block.split(/\r\n|\n|\r/)
        .filter((line) => line.startsWith('data:'))
        // Spring's SSE writer appends the data value directly after `data:`.
        // Preserve leading spaces because model chunks use them between words.
        .map((line) => line.slice(5));
    return data.length ? data.join('\n') : null;
}

function processPayload(payload, state) {
    if (payload === null) return;
    state.rawFull += payload;

    if (!state.sourcesAdded) {
        const header = extractSourcesHeader(state.rawFull);
        if (header) {
            state.sourcesAdded = true;
            renderClippings(state.clippings, header.sources);
            addSourcesToSession(state.query, state.turnNumber, header.sources);
            state.rawFull = state.rawFull.replace(header.comment, '');
        }
    }

    // Do not briefly show the SOURCES metadata as article text while its JSON is arriving.
    if (!state.sourcesAdded && state.rawFull.startsWith('<!--SOURCES:')) return;
    state.rawAnswer = state.rawFull.replace(/<!--SOURCES:[\s\S]*?-->/g, '').trimStart();
    state.turnRecord.answerMarkdown = state.rawAnswer;
    if (state.turnRecord.sources.length === 0) state.turnRecord.status = 'streaming';
    activeChat.updatedAt = Date.now();
    saveActiveChatSoon();
    if (state.rawAnswer.trim()) {
        state.searching.remove();
        renderAnswer(state.answer, state.rawAnswer, true);
    }
}

async function readEventStream(response, state) {
    if (!response.body) throw new Error('The server returned an empty response stream.');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventBoundary;

    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        while ((eventBoundary = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
            const block = buffer.slice(0, eventBoundary.index);
            buffer = buffer.slice(eventBoundary.index + eventBoundary[0].length);
            processPayload(eventPayload(block), state);
        }
    }

    buffer += decoder.decode();
    if (buffer.trim()) processPayload(eventPayload(buffer) ?? buffer, state);
}

function enterThread() {
    document.body.classList.remove('is-home');
    document.body.classList.add('is-thread');
    home.hidden = true;
    thread.hidden = false;
}

function createTurnView(record) {
    const turn = document.createElement('article');
    turn.className = 'turn';
    const headline = document.createElement('h2');
    headline.className = 'user-query';
    headline.textContent = record.query;
    turn.appendChild(headline);

    const clippings = document.createElement('div');
    clippings.className = 'clippings';
    clippings.setAttribute('aria-label', 'Sources');
    renderClippings(clippings, record.sources || []);
    turn.appendChild(clippings);

    const answer = document.createElement('div');
    answer.className = 'answer-body';
    answer.setAttribute('aria-live', 'polite');
    if (record.answerMarkdown) {
        renderAnswer(answer, record.answerMarkdown);
        if (record.status === 'stopped' || record.status === 'interrupted') {
            const note = document.createElement('p');
            note.className = 'stopped-msg';
            note.textContent = record.status === 'interrupted' ? 'This answer was interrupted when the page closed.' : 'Generation stopped.';
            answer.appendChild(note);
        } else if (record.status === 'error') {
            const note = document.createElement('p');
            note.className = 'error-msg';
            note.setAttribute('role', 'alert');
            note.textContent = record.errorMessage || 'The search could not be completed. Try again.';
            answer.appendChild(note);
        }
    } else if (record.status === 'streaming' || record.status === 'interrupted') {
        const message = document.createElement('p');
        message.className = 'stopped-msg';
        message.textContent = 'This answer was interrupted before it could be saved.';
        answer.appendChild(message);
        record.status = 'interrupted';
    } else if (record.status === 'error') {
        const message = document.createElement('div');
        message.className = 'error-msg';
        message.setAttribute('role', 'alert');
        message.textContent = record.errorMessage || 'The search could not be completed. Try again.';
        answer.appendChild(message);
    } else if (record.status === 'stopped') {
        const message = document.createElement('p');
        message.className = 'stopped-msg';
        message.textContent = 'Generation stopped.';
        answer.appendChild(message);
    } else if (record.status === 'legacy-unavailable') {
        const message = document.createElement('p');
        message.className = 'stopped-msg';
        message.textContent = 'Sources were saved for this earlier chat, but its answer text was not stored.';
        answer.appendChild(message);
    } else {
        const message = document.createElement('p');
        message.className = 'stopped-msg';
        message.textContent = 'No answer was returned. Try asking another way.';
        answer.appendChild(message);
    }
    turn.appendChild(answer);

    const actions = document.createElement('div');
    actions.className = 'answer-actions';
    if (record.answerMarkdown) addCopyButton(record.answerMarkdown, actions);
    turn.appendChild(actions);

    const followupSlot = document.createElement('div');
    if (record.followUps?.length) renderFollowUps(followupSlot, record.followUps);
    turn.appendChild(followupSlot);
    return turn;
}

function renderActiveChat() {
    if (!activeChat) return;
    activeChat.turns = activeChat.turns || [];
    thread.replaceChildren(...activeChat.turns.map((record) => createTurnView(record)));
    const hasTurns = activeChat.turns.length > 0;
    home.hidden = hasTurns;
    thread.hidden = !hasTurns;
    document.body.classList.toggle('is-thread', hasTurns);
    document.body.classList.toggle('is-home', !hasTurns);
    renderSessionSources();
}

function renderChatHistory() {
    if (!chatHistoryList) return;
    chatHistoryList.replaceChildren();
    const savedChats = chatRecords
        .filter((chat) => chat.turns?.length)
        .sort((left, right) => right.updatedAt - left.updatedAt);
    if (!savedChats.length) {
        const empty = document.createElement('p');
        empty.className = 'chat-history-empty';
        empty.textContent = 'Your recent chats will appear here.';
        chatHistoryList.appendChild(empty);
        return;
    }
    savedChats.forEach((chat) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'chat-history-item';
        button.textContent = chat.title || 'New chat';
        button.title = button.textContent;
        button.setAttribute('aria-current', String(chat.id === activeChatId));
        button.addEventListener('click', () => selectChat(chat.id));
        chatHistoryList.appendChild(button);
    });
}

function setChatSidebarOpen(open) {
    chatSidebar.classList.toggle('is-open', open);
    chatSidebar.setAttribute('aria-hidden', String(!open));
    chatSidebar.toggleAttribute('inert', !open);
    chatSidebarToggle.setAttribute('aria-expanded', String(open));
    chatSidebarToggle.setAttribute('aria-label', open ? 'Close chat history' : 'Open chat history');
    chatSidebarBackdrop.hidden = !open;
}

function selectChat(chatId) {
    if (isStreaming) {
        chatStorageNotice.textContent = 'Wait for the current answer to finish before switching chats.';
        chatStorageNotice.hidden = false;
        return;
    }
    if (chatId === activeChatId) {
        setChatSidebarOpen(false);
        return;
    }
    const selected = chatRecords.find((chat) => chat.id === chatId);
    if (!selected) return;
    activeChat = selected;
    activeChatId = selected.id;
    writeBrowserValue(ACTIVE_CHAT_KEY, activeChatId);
    closeSessionSources();
    renderActiveChat();
    renderChatHistory();
    setChatSidebarOpen(false);
}

function startNewChat() {
    if (isStreaming) {
        chatStorageNotice.textContent = 'Wait for the current answer to finish before starting a new chat.';
        chatStorageNotice.hidden = false;
        return;
    }
    activeChat = createBlankChat();
    activeChatId = activeChat.id;
    writeBrowserValue(ACTIVE_CHAT_KEY, activeChatId);
    closeSessionSources();
    input.value = '';
    input.style.height = 'auto';
    renderActiveChat();
    renderChatHistory();
    setChatSidebarOpen(false);
    input.focus();
}

async function submitQuery() {
    const query = input.value.trim();
    if (!query || isStreaming) return;

    if (!activeChat) activeChat = createBlankChat();
    enterThread();
    input.value = '';
    input.style.height = 'auto';
    setStreaming(true);

    const turnNumber = getNextSessionTurnNumber();
    const turnRecord = { query, answerMarkdown: '', sources: [], status: 'streaming', errorMessage: '', followUps: [] };
    activeChat.turns.push(turnRecord);
    if (activeChat.turns.length === 1) activeChat.title = query;
    activeChat.updatedAt = Date.now();
    const chatForTurn = activeChat;
    await persistActiveChatNow();

    const turn = createTurnView(turnRecord);
    const clippings = turn.querySelector('.clippings');
    const answer = turn.querySelector('.answer-body');
    const actions = turn.querySelector('.answer-actions');
    const followupSlot = turn.lastElementChild;
    const searching = document.createElement('div');
    searching.className = 'searching';
    searching.innerHTML = '<span class="searching-dash" aria-hidden="true">—</span> Searching the wire…';
    answer.replaceChildren(searching);
    thread.appendChild(turn);
    turn.scrollIntoView({ behavior: 'smooth', block: 'start' });

    const state = { rawFull: '', rawAnswer: '', sourcesAdded: false, clippings, answer, searching, query, turnNumber, turnRecord };
    abortController = new AbortController();
    let stopped = false;
    let requestError = false;
    try {
        const response = await fetch(`${API_BASE}/conversation/stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
            body: JSON.stringify({ query, conversationId: chatForTurn.id }),
            signal: abortController.signal
        });
        if (!response.ok) throw new Error(`Search failed (${response.status}). Please try again.`);
        await readEventStream(response, state);
    } catch (error) {
        stopped = error.name === 'AbortError';
        if (!stopped) {
            requestError = true;
            const message = document.createElement('div');
            message.className = 'error-msg';
            message.setAttribute('role', 'alert');
            message.textContent = error.message || 'The search could not be completed. Check your connection and try again.';
            if (!state.rawAnswer.trim()) {
                searching.remove();
                answer.replaceChildren(message);
            } else {
                answer.appendChild(message);
            }
            turnRecord.status = 'error';
            turnRecord.errorMessage = message.textContent;
        }
    } finally {
        abortController = null;
        setStreaming(false);
    }

    if (!state.rawAnswer.trim()) {
        if (stopped) {
            searching.textContent = 'Generation stopped.';
            searching.classList.add('stopped-msg');
            turnRecord.status = 'stopped';
        } else if (!answer.querySelector('.error-msg')) {
            searching.textContent = 'No answer was returned. Try asking another way.';
            searching.classList.add('stopped-msg');
            turnRecord.status = 'empty';
        }
        activeChat.updatedAt = Date.now();
        await persistActiveChatNow();
        return;
    }

    searching.remove();
    renderAnswer(answer, state.rawAnswer);
    if (stopped) {
        const stoppedNote = document.createElement('p');
        stoppedNote.className = 'stopped-msg';
        stoppedNote.textContent = 'Generation stopped.';
        answer.appendChild(stoppedNote);
        turnRecord.status = 'stopped';
    } else if (requestError) {
        const errorNote = document.createElement('p');
        errorNote.className = 'error-msg';
        errorNote.setAttribute('role', 'alert');
        errorNote.textContent = turnRecord.errorMessage || 'The search could not be completed. Check your connection and try again.';
        answer.appendChild(errorNote);
        turnRecord.status = 'error';
    } else {
        turnRecord.status = 'completed';
    }
    addCopyButton(state.rawAnswer, actions);
    activeChat.updatedAt = Date.now();
    await persistActiveChatNow();
    if (!stopped) loadFollowUps(query, state.rawAnswer, followupSlot, turnRecord, chatForTurn);
}

input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
});

input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        submitQuery();
    }
});

document.querySelectorAll('.wire').forEach((button) => {
    button.addEventListener('click', () => {
        input.value = button.textContent.trim();
        submitQuery();
    });
});

form.addEventListener('submit', (event) => {
    event.preventDefault();
    submitQuery();
});

form.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target : event.target.parentElement;
    if (target?.closest('button')) return;
    input.focus();
});

stopBtn.addEventListener('click', () => abortController?.abort());

chatSidebarToggle.addEventListener('click', () => setChatSidebarOpen(!chatSidebar.classList.contains('is-open')));
chatSidebarClose.addEventListener('click', () => {
    setChatSidebarOpen(false);
    chatSidebarToggle.focus();
});
chatSidebarBackdrop.addEventListener('click', () => setChatSidebarOpen(false));
newChatButton.addEventListener('click', startNewChat);

sessionSourcesToggle.addEventListener('click', () => {
    const opening = sessionSourcesPanel.hidden;
    sessionSourcesPanel.hidden = !opening;
    sessionSourcesToggle.setAttribute('aria-expanded', String(opening));
});

sessionSourcesClose.addEventListener('click', () => closeSessionSources(true));

document.addEventListener('click', (event) => {
    if (sessionSourcesPanel.hidden) return;
    if (!sessionSourcesPanel.contains(event.target) && !sessionSourcesToggle.contains(event.target)) {
        closeSessionSources();
    }
});

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !sessionSourcesPanel.hidden) closeSessionSources(true);
    if (event.key === 'Escape' && chatSidebar.classList.contains('is-open')) {
        setChatSidebarOpen(false);
        chatSidebarToggle.focus();
    }
});

initialiseChatHistory();
