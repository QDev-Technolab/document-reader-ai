// ---- State ----
let isStreaming = false;
let currentConversationId = null;
let lastAssistantMsgId = null;

// ---- Elements ----
const chatArea = document.getElementById('chat-area');
const questionEl = document.getElementById('question');
const sendBtn = document.getElementById('send-btn');
const sidebar = document.getElementById('sidebar');
const overlay = document.getElementById('sidebar-overlay');
const statusDot = document.getElementById('status-dot');
const statusText = document.getElementById('status-text');
const uploadZone = document.getElementById('upload-zone');
const uploadProg = document.getElementById('upload-progress');
const fileInput = document.getElementById('file-input');
const docList = document.getElementById('doc-list');
const convList = document.getElementById('conv-list');
const topbarTitle = document.querySelector('.topbar-title');
const themeToggleBtn = document.getElementById('theme-toggle');
const hljsThemeLink = document.getElementById('hljs-theme');

// ---- Theme ----
const HLJS_DARK  = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css';
const HLJS_LIGHT = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css';

function applyTheme(theme) {
    if (theme === 'light') {
        document.body.classList.add('light');
        hljsThemeLink.href = HLJS_LIGHT;
    } else {
        document.body.classList.remove('light');
        hljsThemeLink.href = HLJS_DARK;
    }
}

function toggleTheme() {
    const isLight = document.body.classList.contains('light');
    const next = isLight ? 'dark' : 'light';
    applyTheme(next);
    localStorage.setItem('di-theme', next);
}

themeToggleBtn.addEventListener('click', toggleTheme);

// Apply saved or system preference on load
(function initTheme() {
    const saved = localStorage.getItem('di-theme');
    if (saved) {
        applyTheme(saved);
    } else if (window.matchMedia('(prefers-color-scheme: light)').matches) {
        applyTheme('light');
    }
    // default: dark (no class needed)
})();

// ---- Markdown config ----
marked.setOptions({
    highlight: (code, lang) => {
        if (lang && hljs.getLanguage(lang))
            return hljs.highlight(code, { language: lang }).value;
        return hljs.highlightAuto(code).value;
    },
    breaks: true,
    gfm: true
});

// ---- Auto-resize textarea ----
questionEl.addEventListener('input', () => {
    questionEl.style.height = 'auto';
    questionEl.style.height = Math.min(questionEl.scrollHeight, 150) + 'px';
});

questionEl.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
});

sendBtn.addEventListener('click', sendMessage);

// ---- Chat ----
async function sendMessage(options = {}) {
    const questionSource = typeof options.text === 'string' ? options.text : questionEl.value;
    const question = questionSource.trim();
    if (!question || isStreaming) return;

    isStreaming = true;
    sendBtn.disabled = true;
    if (typeof options.text !== 'string') {
        questionEl.value = '';
    }
    questionEl.style.height = 'auto';

    const welcome = document.getElementById('welcome');
    if (welcome) welcome.remove();

    const explicitParentId = Object.prototype.hasOwnProperty.call(options, 'parentId');
    const parentId = explicitParentId ? options.parentId : (lastAssistantMsgId ?? getTailMessageId());
    const isEdit = Boolean(options.isEdit);
    const replaceFromMsgEl = options.replaceFromMsgEl || null;

    if (replaceFromMsgEl) {
        removeMessagesFromElement(replaceFromMsgEl);
    }

    const userMsgEl = addUserMessage({
        role: 'user',
        content: question,
        parentId: parentId ?? null,
        siblingCount: 1,
        siblingIndex: 1
    });
    const { contentEl, thinkingEl } = addAssistantPlaceholder();
    const startTime = performance.now();

    try {
        const reqBody = { question };
        if (currentConversationId != null) {
            reqBody.conversationId = currentConversationId;
        }
        if (parentId != null) {
            reqBody.parentId = parentId;
        }
        if (isEdit) {
            reqBody.isEdit = true;
        }

        const res = await fetch('/chat/ask-stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(reqBody)
        });

        if (!res.ok) throw new Error('Server error: ' + res.status);

        let firstToken = true;
        let streamDone = false;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let fullText = '';
        let currentEvent = 'message';
        let dataLines = [];
        let renderRAF = 0;
        let assistantMeta = null;

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();

            for (const line of lines) {
                if (line === '') {
                    // SSE event boundary — dispatch accumulated event
                    if (dataLines.length > 0) {
                        const data = dataLines.join('\n');

                        if (currentEvent === 'conversationId') {
                            currentConversationId = parseInt(data.trim(), 10);
                        } else if (currentEvent === 'userMessage') {
                            try {
                                const userData = JSON.parse(data);
                                setMessageMetadata(userMsgEl, {
                                    id: userData.id,
                                    parentId: userData.parentId,
                                    siblingCount: userData.siblingCount || 1,
                                    siblingIndex: userData.siblingIndex || 1
                                });
                            } catch (_) { /* ignore malformed userMessage payload */ }
                        } else if (currentEvent === 'done') {
                            streamDone = true;
                            try {
                                assistantMeta = JSON.parse(data);
                            } catch (_) { /* done payload can be empty */ }
                            if (renderRAF) { cancelAnimationFrame(renderRAF); renderRAF = 0; }
                            finishStream(contentEl, fullText, startTime, assistantMeta);
                        } else if (currentEvent === 'error') {
                            if (firstToken) thinkingEl.remove();
                            contentEl.innerHTML = '<span class="error-text">Error: ' + escapeHtml(data) + '</span>';
                            streamDone = true;
                        } else if (currentEvent === 'token') {
                            if (firstToken) {
                                thinkingEl.remove();
                                firstToken = false;
                            }
                            try {
                                const tokenData = JSON.parse(data);
                                fullText += tokenData.t;
                            } catch (e) {
                                fullText += data;
                            }
                            // Throttle rendering to once per animation frame
                            if (!renderRAF) {
                                renderRAF = requestAnimationFrame(() => {
                                    renderMarkdown(contentEl, fullText);
                                    scrollToBottom();
                                    renderRAF = 0;
                                });
                            }
                        }
                    }
                    currentEvent = 'message';
                    dataLines = [];
                    continue;
                }

                if (line.startsWith('event:')) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith('data:')) {
                    dataLines.push(line.substring(5));
                }
            }
        }

        if (firstToken) thinkingEl.remove();
        if (!streamDone) {
            if (renderRAF) { cancelAnimationFrame(renderRAF); renderRAF = 0; }
            finishStream(contentEl, fullText, startTime, assistantMeta);
        }

    } catch (err) {
        thinkingEl?.remove();
        contentEl.innerHTML = '<span class="error-text">Failed to connect: ' + escapeHtml(err.message) + '</span>';
    }

    isStreaming = false;
    sendBtn.disabled = false;
    questionEl.focus();

    // Refresh conversation list so the new/updated conversation appears
    recomputeLastAssistantMessageId();
    loadConversations();
}

function finishStream(contentEl, fullText, startTime, assistantMeta) {
    const cursor = contentEl.querySelector('.cursor-blink');
    if (cursor) cursor.remove();

    if (fullText) {
        renderMarkdown(contentEl, fullText, true);
    }

    // Store raw text on the message element
    const msgEl = contentEl.closest('.msg');
    if (msgEl) {
        msgEl.dataset.rawText = fullText;
        if (assistantMeta && assistantMeta.assistantMessageId) {
            setMessageMetadata(msgEl, {
                id: assistantMeta.assistantMessageId,
                parentId: assistantMeta.assistantParentId,
                siblingCount: assistantMeta.assistantSiblingCount || 1,
                siblingIndex: assistantMeta.assistantSiblingIndex || 1
            });
        }
    }

    const elapsed = ((performance.now() - startTime) / 1000).toFixed(1);
    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    meta.textContent = elapsed + 's';
    contentEl.parentElement.appendChild(meta);

    // Add copy button
    const actions = document.createElement('div');
    actions.className = 'msg-actions';
    actions.innerHTML = `
        <button class="action-btn copy-btn" title="Copy">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        </button>`;
    actions.querySelector('.copy-btn').addEventListener('click', () => copyResponse(msgEl));
    contentEl.parentElement.appendChild(actions);
}

function renderMarkdown(el, text, isFinal = false) {
    if (isFinal) {
        el.innerHTML = marked.parse(text);
        el.querySelectorAll('pre code').forEach(block => hljs.highlightElement(block));
    } else {
        // Keep markdown formatting live while streaming; defer syntax highlighting until final render.
        el.innerHTML = marked.parse(text) + '<span class="cursor-blink"></span>';
    }
}

function addUserMessage(message) {
    const payload = typeof message === 'string'
        ? { role: 'user', content: message }
        : message;
    const msg = document.createElement('div');
    msg.className = 'msg user';
    msg.dataset.text = payload.content;
    msg.innerHTML = `
    <div class="msg-inner">
        <div class="msg-avatar">U</div>
        <div class="msg-body">
            <div class="bubble-row">
                <div class="msg-content"></div>
                <button class="inline-edit-btn" title="Edit message">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                </button>
            </div>
            <div class="edit-area">
                <textarea rows="1"></textarea>
                <div class="edit-buttons">
                    <button class="cancel-btn">Cancel</button>
                    <button class="save-btn">Save & Submit</button>
                </div>
            </div>
            <div class="version-nav">
                <button class="version-prev" type="button" title="Previous version">&lt;</button>
                <span class="version-label">1/1</span>
                <button class="version-next" type="button" title="Next version">&gt;</button>
            </div>
        </div>
    </div>`;
    msg.querySelector('.msg-content').textContent = payload.content;
    setMessageMetadata(msg, {
        id: payload.id,
        parentId: payload.parentId,
        siblingCount: payload.siblingCount || 1,
        siblingIndex: payload.siblingIndex || 1
    });

    const editBtn = msg.querySelector('.inline-edit-btn');
    const editArea = msg.querySelector('.edit-area');
    const editTextarea = editArea.querySelector('textarea');
    const prevBtn = msg.querySelector('.version-prev');
    const nextBtn = msg.querySelector('.version-next');

    editBtn.addEventListener('click', () => {
        if (isStreaming) return;
        msg.classList.add('editing');
        editTextarea.value = msg.dataset.text;
        // Auto-resize textarea
        setTimeout(() => {
            editTextarea.style.height = 'auto';
            editTextarea.style.height = editTextarea.scrollHeight + 'px';
            editTextarea.focus();
            editTextarea.setSelectionRange(editTextarea.value.length, editTextarea.value.length);
        }, 0);
    });

    editArea.querySelector('.cancel-btn').addEventListener('click', () => {
        msg.classList.remove('editing');
    });

    editArea.querySelector('.save-btn').addEventListener('click', () => {
        const newText = editTextarea.value.trim();
        if (!newText) return;
        submitEditedMessage(msg, newText);
    });

    // Auto-resize textarea on input
    editTextarea.addEventListener('input', () => {
        editTextarea.style.height = 'auto';
        editTextarea.style.height = editTextarea.scrollHeight + 'px';
    });

    // Enter to submit, Shift+Enter for new line
    editTextarea.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            const newText = editTextarea.value.trim();
            if (newText) submitEditedMessage(msg, newText);
        }
        if (e.key === 'Escape') {
            msg.classList.remove('editing');
        }
    });

    prevBtn.addEventListener('click', () => switchUserVersion(msg, -1));
    nextBtn.addEventListener('click', () => switchUserVersion(msg, 1));

    chatArea.appendChild(msg);
    scrollToBottom();
    return msg;
}

function submitEditedMessage(msgEl, newText) {
    const rawParent = msgEl.dataset.parentId;
    const parentId = rawParent ? Number(rawParent) : null;
    msgEl.classList.remove('editing');
    sendMessage({
        text: newText,
        parentId,
        isEdit: true,
        replaceFromMsgEl: msgEl
    });
}

function addAssistantMessage(message) {
    const payload = typeof message === 'string'
        ? { role: 'assistant', content: message }
        : message;
    const msg = document.createElement('div');
    msg.className = 'msg assistant';
    msg.dataset.rawText = payload.content;
    msg.innerHTML = `
    <div class="msg-inner">
        <div class="msg-avatar">AI</div>
        <div class="msg-body">
            <div class="msg-content"></div>
            <div class="msg-actions">
                <button class="action-btn copy-btn" title="Copy">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                </button>
            </div>
        </div>
    </div>`;
    setMessageMetadata(msg, {
        id: payload.id,
        parentId: payload.parentId,
        siblingCount: payload.siblingCount || 1,
        siblingIndex: payload.siblingIndex || 1
    });
    const c = msg.querySelector('.msg-content');
    c.innerHTML = marked.parse(payload.content);
    c.querySelectorAll('pre code').forEach(block => hljs.highlightElement(block));
    msg.querySelector('.copy-btn').addEventListener('click', () => copyResponse(msg));
    chatArea.appendChild(msg);
    return msg;
}

function addAssistantPlaceholder() {
    const msg = document.createElement('div');
    msg.className = 'msg assistant';
    msg.innerHTML = `
    <div class="msg-inner">
        <div class="msg-avatar">AI</div>
        <div class="msg-body">
            <div class="msg-content"></div>
        </div>
    </div>`;
    const contentEl = msg.querySelector('.msg-content');

    const thinking = document.createElement('div');
    thinking.className = 'thinking-dots';
    thinking.innerHTML = '<span></span><span></span><span></span>';
    contentEl.appendChild(thinking);

    chatArea.appendChild(msg);
    scrollToBottom();
    return { contentEl, thinkingEl: thinking, msgEl: msg };
}

function setMessageMetadata(msgEl, metadata) {
    if (!msgEl || !metadata) return;
    if (metadata.id != null) {
        msgEl.dataset.msgId = String(metadata.id);
    }

    if (metadata.parentId == null || metadata.parentId === '') {
        msgEl.dataset.parentId = '';
    } else {
        msgEl.dataset.parentId = String(metadata.parentId);
    }

    const siblingCount = Number(metadata.siblingCount || 1);
    const siblingIndex = Number(metadata.siblingIndex || 1);
    msgEl.dataset.siblingCount = String(siblingCount);
    msgEl.dataset.siblingIndex = String(siblingIndex);
    updateVersionNav(msgEl, siblingCount, siblingIndex);
}

function updateVersionNav(msgEl, siblingCount, siblingIndex) {
    if (!msgEl.classList.contains('user')) return;
    const nav = msgEl.querySelector('.version-nav');
    if (!nav) return;

    const label = nav.querySelector('.version-label');
    const prevBtn = nav.querySelector('.version-prev');
    const nextBtn = nav.querySelector('.version-next');
    label.textContent = siblingIndex + '/' + siblingCount;

    if (siblingCount > 1) {
        nav.classList.add('active');
    } else {
        nav.classList.remove('active');
    }

    prevBtn.disabled = siblingIndex <= 1;
    nextBtn.disabled = siblingIndex >= siblingCount;
}

async function switchUserVersion(msgEl, delta) {
    if (isStreaming) return;
    const msgId = Number(msgEl.dataset.msgId || 0);
    if (!msgId || !currentConversationId) return;

    try {
        const res = await fetch('/chat/conversations/' + currentConversationId + '/messages/' + msgId + '/siblings');
        if (!res.ok) return;
        const siblings = await res.json();
        const currentIndex = Number(msgEl.dataset.siblingIndex || 1);
        const targetIndex = currentIndex + delta;
        if (targetIndex < 1 || targetIndex > siblings.length) return;

        const target = siblings[targetIndex - 1];
        if (!target || !target.id) return;

        await replaceThreadFromElement(msgEl, target.id);
    } catch (_) { /* silent */ }
}

async function replaceThreadFromElement(fromMsgEl, targetMessageId) {
    const res = await fetch('/chat/conversations/' + currentConversationId + '/thread-from/' + targetMessageId);
    if (!res.ok) throw new Error('Failed to switch version');
    const messages = await res.json();

    removeMessagesFromElement(fromMsgEl);
    renderThreadMessages(messages, false);
    recomputeLastAssistantMessageId();
}

function renderThreadMessages(messages, clear = true) {
    if (clear) {
        chatArea.innerHTML = '';
    }

    messages.forEach(m => {
        if (m.role === 'user') {
            addUserMessage(m);
        } else {
            addAssistantMessage(m);
        }
    });
}

function removeMessagesFromElement(startMsgEl) {
    const allMsgs = Array.from(chatArea.querySelectorAll('.msg'));
    const idx = allMsgs.indexOf(startMsgEl);
    if (idx < 0) return;
    for (let i = allMsgs.length - 1; i >= idx; i--) {
        allMsgs[i].remove();
    }
}

function getTailMessageId() {
    const lastMsg = chatArea.querySelector('.msg:last-of-type');
    if (!lastMsg) return null;
    const id = Number(lastMsg.dataset.msgId || 0);
    return id || null;
}

function recomputeLastAssistantMessageId() {
    const assistantMsgs = Array.from(chatArea.querySelectorAll('.msg.assistant'));
    for (let i = assistantMsgs.length - 1; i >= 0; i--) {
        const id = Number(assistantMsgs[i].dataset.msgId || 0);
        if (id) {
            lastAssistantMsgId = id;
            return;
        }
    }
    lastAssistantMsgId = null;
}

function scrollToBottom() {
    chatArea.scrollTop = chatArea.scrollHeight;
}

function showWelcome() {
    chatArea.innerHTML = `
    <div class="welcome" id="welcome">
        <div class="welcome-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="1.8">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
                <line x1="16" y1="13" x2="8" y2="13"/>
                <line x1="16" y1="17" x2="8" y2="17"/>
            </svg>
        </div>
        <h2>Document Intelligence</h2>
        <p>Upload a document and ask anything about it. Get precise answers with real-time streaming.</p>
        <div class="welcome-tips">
            <div class="welcome-tip">
                <strong>Ask anything</strong>
                Summarize, compare, or deep-dive into your document
            </div>
            <div class="welcome-tip">
                <strong>Multi-format</strong>
                Supports PDF, Word, Excel, and plain text files
            </div>
            <div class="welcome-tip">
                <strong>Real-time</strong>
                Answers stream token-by-token as they generate
            </div>
        </div>
    </div>`;
    lastAssistantMsgId = null;
}

function newChat() {
    currentConversationId = null;
    lastAssistantMsgId = null;
    topbarTitle.textContent = 'New Chat';
    showWelcome();
    highlightActiveConversation();
    closeSidebarOnMobile();
}

function escapeHtml(str) {
    const d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

function copyResponse(msgEl) {
    const rawText = msgEl.dataset.rawText || msgEl.querySelector('.msg-content')?.innerText || '';
    navigator.clipboard.writeText(rawText).then(() => {
        const btn = msgEl.querySelector('.copy-btn');
        if (btn) {
            btn.classList.add('copied');
            const origHTML = btn.innerHTML;
            btn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>`;
            setTimeout(() => {
                btn.innerHTML = origHTML;
                btn.classList.remove('copied');
            }, 2000);
        }
    });
}

// ---- Conversations (database-backed) ----
async function loadConversations() {
    try {
        const res = await fetch('/chat/conversations');
        const conversations = await res.json();

        convList.innerHTML = '<h3>Conversations</h3>';

        if (conversations.length === 0) {
            const empty = document.createElement('div');
            empty.style.cssText = 'padding: 8px 10px; font-size: 12px; color: var(--text-muted);';
            empty.textContent = 'No conversations yet';
            convList.appendChild(empty);
            return;
        }

        conversations.forEach(conv => {
            const item = document.createElement('div');
            item.className = 'conv-item' + (conv.id === currentConversationId ? ' active' : '');
            item.dataset.id = conv.id;
            item.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                <span class="conv-title" title="${escapeHtml(conv.title)}">${escapeHtml(conv.title)}</span>
                <button class="conv-delete" title="Delete conversation" onclick="event.stopPropagation(); deleteConversation(${conv.id})">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>`;
            item.addEventListener('click', () => selectConversation(conv.id, conv.title));
            convList.appendChild(item);
        });
    } catch (_) { /* silent */ }
}

async function selectConversation(id, title) {
    if (isStreaming) return;
    currentConversationId = id;
    topbarTitle.textContent = title || 'Chat';
    highlightActiveConversation();
    closeSidebarOnMobile();

    // Clear chat area and load latest thread from DB
    chatArea.innerHTML = '';

    try {
        const res = await fetch('/chat/conversations/' + id + '/thread');
        const messages = await res.json();

        if (messages.length === 0) {
            showWelcome();
            return;
        }

        renderThreadMessages(messages);
        recomputeLastAssistantMessageId();
        scrollToBottom();
    } catch (err) {
        chatArea.innerHTML = '<div style="padding:20px;color:var(--error);">Failed to load messages.</div>';
    }
}

async function deleteConversation(id) {
    const confirmed = await showConfirmModal({
        title: 'Delete conversation?',
        body: 'This conversation will be permanently removed. This cannot be undone.'
    });
    if (!confirmed) return;
    try {
        const res = await fetch('/chat/conversations/' + id, { method: 'DELETE' });
        if (res.ok) {
            if (currentConversationId === id) {
                newChat();
            }
            loadConversations();
        }
    } catch (_) { /* silent */ }
}

function highlightActiveConversation() {
    convList.querySelectorAll('.conv-item').forEach(el => {
        el.classList.toggle('active', Number(el.dataset.id) === currentConversationId);
    });
}

// ---- Sidebar ----
function toggleSidebar() {
    const isCollapsed = sidebar.classList.toggle('collapsed');

    // Only use overlay on mobile
    if (window.innerWidth <= 768) {
        overlay.classList.toggle('active', !isCollapsed);
    } else {
        overlay.classList.remove('active');
    }
}

function closeSidebarOnMobile() {
    if (window.innerWidth <= 768 && !sidebar.classList.contains('collapsed')) {
        toggleSidebar();
    }
}

// ---- File upload ----
uploadZone.addEventListener('dragover', e => { e.preventDefault(); uploadZone.classList.add('dragover'); });
uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('dragover'));
uploadZone.addEventListener('drop', e => {
    e.preventDefault();
    uploadZone.classList.remove('dragover');
    if (e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
});
fileInput.addEventListener('change', () => { if (fileInput.files.length) uploadFile(fileInput.files[0]); });

async function uploadFile(file) {
    const allowed = ['application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'text/plain',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.ms-excel'];
    if (!allowed.includes(file.type) && !file.name.match(/\.(pdf|docx|txt|xlsx|xls)$/i)) {
        uploadProg.textContent = 'Unsupported file type';
        uploadProg.style.color = 'var(--error)';
        uploadProg.classList.add('active');
        return;
    }

    uploadProg.style.color = 'var(--accent)';
    uploadProg.textContent = 'Uploading ' + file.name + '...';
    uploadProg.classList.add('active');

    try {
        const fd = new FormData();
        fd.append('file', file);

        const res = await fetch('/chat/upload', { method: 'POST', body: fd });
        const data = await res.json();

        if (res.ok) {
            uploadProg.textContent = 'Processed: ' + file.name;
            loadDocuments();
        } else {
            uploadProg.textContent = 'Error: ' + (data.error || 'Upload failed');
            uploadProg.style.color = 'var(--error)';
        }
    } catch (err) {
        uploadProg.textContent = 'Upload failed: ' + err.message;
        uploadProg.style.color = 'var(--error)';
    }

    fileInput.value = '';
}

// ---- Load documents ----
async function loadDocuments() {
    try {
        const res = await fetch('/chat/documents');
        const docs = await res.json();

        docList.innerHTML = '<h3>Documents</h3>';

        if (docs.length === 0) {
            const empty = document.createElement('div');
            empty.style.cssText = 'padding: 8px 10px; font-size: 12px; color: var(--text-muted);';
            empty.textContent = 'No documents uploaded yet';
            docList.appendChild(empty);
            return;
        }

        docs.forEach(doc => {
            const item = document.createElement('div');
            item.className = 'doc-item';
            item.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                <span title="${escapeHtml(doc.filename)}">${escapeHtml(doc.filename)}</span>
                <span class="chunk-count">${doc.totalChunks || '?'} chunks</span>
                <button class="delete-btn" title="Delete document" onclick="deleteDocument(${doc.id}, '${escapeHtml(doc.filename)}')">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>`;
            docList.appendChild(item);
        });
    } catch (_) { /* silent */ }
}

async function deleteDocument(id, filename) {
    const confirmed = await showConfirmModal({
        title: 'Delete document?',
        body: '\u201c' + filename + '\u201d and all its chunks will be permanently removed. This cannot be undone.'
    });
    if (!confirmed) return;
    try {
        const res = await fetch('/chat/documents/' + id, { method: 'DELETE' });
        if (res.ok) {
            loadDocuments();
        }
    } catch (_) { /* silent */ }
}

// ---- Confirm Modal ----
function showConfirmModal({ title, body }) {
    return new Promise(resolve => {
        const backdrop = document.getElementById('confirm-modal');
        const titleEl = document.getElementById('modal-title');
        const bodyEl = document.getElementById('modal-body');
        const cancelBtn = document.getElementById('modal-cancel');
        const confirmBtn = document.getElementById('modal-confirm');

        titleEl.textContent = title;
        bodyEl.textContent = body;
        backdrop.classList.add('active');
        cancelBtn.focus();

        function close(result) {
            backdrop.classList.remove('active');
            document.removeEventListener('keydown', onKey);
            cancelBtn.removeEventListener('click', onCancel);
            confirmBtn.removeEventListener('click', onConfirm);
            resolve(result);
        }

        function onCancel() { close(false); }
        function onConfirm() { close(true); }
        function onKey(e) {
            if (e.key === 'Escape') close(false);
            if (e.key === 'Enter') close(true);
        }

        cancelBtn.addEventListener('click', onCancel);
        confirmBtn.addEventListener('click', onConfirm);
        document.addEventListener('keydown', onKey);

        // Close on backdrop click (outside modal box)
        backdrop.addEventListener('click', function onBackdropClick(e) {
            if (e.target === backdrop) {
                backdrop.removeEventListener('click', onBackdropClick);
                close(false);
            }
        });
    });
}

// ---- Status check ----
async function checkStatus() {
    try {
        const res = await fetch('/chat/status');
        const data = await res.json();
        statusDot.classList.toggle('online', data.llamaReady);
        statusDot.classList.toggle('offline', !data.llamaReady);
        statusText.textContent = data.llamaReady ? 'Ollama Online' : 'Ollama Offline';
    } catch {
        statusDot.classList.add('offline');
        statusText.textContent = 'Offline';
    }
}

// ---- Init ----
checkStatus();
loadDocuments();
loadConversations();

function handleResponsiveSidebar() {
    if (window.innerWidth <= 768) {
        sidebar.classList.add('collapsed');
        overlay.classList.remove('active');
    } else {
        sidebar.classList.remove('collapsed');
        overlay.classList.remove('active');
    }
}

handleResponsiveSidebar();
window.addEventListener('resize', handleResponsiveSidebar);

overlay.addEventListener('click', () => {
    sidebar.classList.add('collapsed');
    overlay.classList.remove('active');
});
