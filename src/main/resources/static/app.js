/**
 * WebSSH 前端应用主文件
 *
 * 功能概述：
 * - 多标签终端管理（创建、切换、关闭标签）
 * - SSH 连接管理（通过 WebSocket 与后端通信）
 * - SFTP 文件浏览器（列表、上传、下载、创建目录）
 * - 端口转发管理（本地转发 / 远程转发）
 * - 会话配置持久化（保存/加载/删除已保存的 SSH 会话）
 * - 终端主题切换（6 种配色方案）
 * - 国际化支持（中/英文切换）
 * - 全屏模式
 *
 * 架构：
 * - 使用 xterm.js 作为终端渲染引擎
 * - 通过 WebSocket（/ws/ssh）与后端 SshWebSocketHandler 通信
 * - 所有消息均为 JSON 格式，通过 type 字段区分消息类型
 */

// ==================== 终端主题定义 ====================

const TERMINAL_THEMES = {
    "default": {
        background: "#0b0f14",
        foreground: "#dbe5f2",
        cursor: "#5cc8ff",
        cursorAccent: "#0b0f14",
        selectionBackground: "rgba(92,200,255,0.18)"
    },
    "orange": {
        background: "#0c0c0c",
        foreground: "#e0e0e0",
        cursor: "#ff8c00",
        cursorAccent: "#0c0c0c",
        selectionBackground: "rgba(255,140,0,0.18)"
    },
    "green": {
        background: "#0a0e0a",
        foreground: "#c8e6c9",
        cursor: "#00e676",
        cursorAccent: "#0a0e0a",
        selectionBackground: "rgba(0,230,118,0.18)"
    },
    "amber": {
        background: "#1a1200",
        foreground: "#ffd54f",
        cursor: "#ffab00",
        cursorAccent: "#1a1200",
        selectionBackground: "rgba(255,213,79,0.18)"
    },
    "purple": {
        background: "#0e0b14",
        foreground: "#d1c4e9",
        cursor: "#b388ff",
        cursorAccent: "#0e0b14",
        selectionBackground: "rgba(179,136,255,0.18)"
    },
    "red": {
        background: "#120808",
        foreground: "#efcfcf",
        cursor: "#ff5252",
        cursorAccent: "#120808",
        selectionBackground: "rgba(255,82,82,0.18)"
    }
};

/** localStorage 中保存当前主题 ID 的键名 */
const THEME_STORAGE_KEY = "webssh.terminalTheme";

/** 从 localStorage 读取已保存的主题 ID，无效时回退到 "default" */
function getSavedThemeId() {
    try {
        const id = localStorage.getItem(THEME_STORAGE_KEY);
        return (id && TERMINAL_THEMES[id]) ? id : "default";
    } catch (_) {
        return "default";
    }
}

/** 获取当前终端主题配置对象 */
function getTerminalTheme() {
    return TERMINAL_THEMES[getSavedThemeId()];
}

/** 将十六进制颜色值转换为 "r, g, b" 格式，用于 CSS rgba() 变量 */
function hexToRgb(hex) {
    const n = parseInt(hex.replace("#", ""), 16);
    return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

/** 将主题的主色调应用到 CSS 自定义属性，使整个 UI 配色与终端主题协调 */
function applyThemeCssVars(theme) {
    const root = document.documentElement;
    root.style.setProperty("--theme-accent", theme.cursor);
    root.style.setProperty("--theme-accent-rgb", hexToRgb(theme.cursor));
}

/** 切换主题：更新所有已打开标签的终端配色，保存选择到 localStorage */
function applyThemeToAllTerminals(themeId) {
    const theme = TERMINAL_THEMES[themeId];
    if (!theme) return;
    try {
        localStorage.setItem(THEME_STORAGE_KEY, themeId);
    } catch (_) { /* ignore */ }
    tabs.forEach((tab) => {
        tab.term.options.theme = theme;
    });
    applyThemeCssVars(theme);
    const sel = document.getElementById("themeSelect");
    if (sel && sel.value !== themeId) {
        sel.value = themeId;
    }
    const dot = document.getElementById("themeColorDot");
    if (dot) {
        dot.style.background = theme.cursor;
    }
}

// ==================== 国际化初始化 ====================

const i18n = window.webSshI18n;
if (!i18n) {
    throw new Error("i18n.js 未加载");
}

const { applyTranslations, getLanguage, mountLanguageSelector, t, translateKnownMessage } = i18n;

// ==================== DOM 元素引用 ====================

const terminalContainer = document.getElementById("terminalContainer");
const tabsEl = document.getElementById("tabs");
const sessionListEl = document.getElementById("sessionList");
const currentUserEl = document.getElementById("currentUser");
const avatarLetterEl = document.getElementById("avatarLetter");
const languageSelectEl = document.getElementById("languageSelect");

// --- 配置抽屉面板 ---
const configDrawer = document.getElementById("configDrawer");
const openAddSessionBtn = document.getElementById("openAddSessionBtn");
const closeDrawerBtn = document.getElementById("closeDrawerBtn");
const statusOverlay = document.getElementById("statusOverlay");

// --- SSH 连接表单元素 ---
const formEls = {
    sessionName: document.getElementById("sessionName"),
    host: document.getElementById("host"),
    port: document.getElementById("port"),
    username: document.getElementById("username"),
    authType: document.getElementById("authType"),
    password: document.getElementById("password"),
    privateKey: document.getElementById("privateKey"),
    passphrase: document.getElementById("passphrase"),
    saveCredentials: document.getElementById("saveCredentials"),
    hostFingerprint: document.getElementById("hostFingerprint")
};

const connectBtn = document.getElementById("connectBtn");
const disconnectBtn = document.getElementById("disconnectBtn");
const saveSessionBtn = document.getElementById("saveSessionBtn");
const newTabBtn = document.getElementById("newTabBtn");
const sftpToggleBtn = document.getElementById("sftpToggleBtn");
const fullscreenBtn = document.getElementById("fullscreenBtn");
const mobileKeybar = document.getElementById("mobileKeybar");
const mobileKeybarToggle = document.getElementById("mobileKeybarToggle");
const terminalContextMenu = document.getElementById("terminalContextMenu");
const terminalContextCopyBtn = terminalContextMenu
    ? terminalContextMenu.querySelector("[data-action=\"copy\"]")
    : null;

// --- SFTP 面板元素 ---
const sftpEls = {
    panel: document.getElementById("sftpPanel"),
    closeBtn: document.getElementById("sftpCloseBtn"),
    state: document.getElementById("sftpState"),
    path: document.getElementById("sftpPathInput"),
    upBtn: document.getElementById("sftpUpBtn"),
    refreshBtn: document.getElementById("sftpRefreshBtn"),
    mkdirBtn: document.getElementById("sftpMkdirBtn"),
    uploadBtn: document.getElementById("sftpUploadBtn"),
    uploadFolderBtn: document.getElementById("sftpUploadFolderBtn"),
    uploadFileInput: document.getElementById("sftpUploadFileInput"),
    uploadFolderInput: document.getElementById("sftpUploadFolderInput"),
    entries: document.getElementById("sftpEntries"),
    toggleBtn: sftpToggleBtn
};

// --- 端口转发面板元素 ---
const forwardEls = {
    direction: document.getElementById("forwardDirection"),
    bindHost: document.getElementById("forwardBindHost"),
    bindPort: document.getElementById("forwardBindPort"),
    targetHost: document.getElementById("forwardTargetHost"),
    targetPort: document.getElementById("forwardTargetPort"),
    addBtn: document.getElementById("addForwardBtn"),
    refreshBtn: document.getElementById("refreshForwardBtn"),
    list: document.getElementById("forwardList")
};

// ==================== 常量与全局状态 ====================

/** SFTP 上传分片大小：512KB */
const SFTP_UPLOAD_CHUNK_BYTES = 512 * 1024;
/** WebSocket 上传缓冲区上限：超过 2MB 时暂停发送，实现简易背压 */
const SFTP_UPLOAD_MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
/** WebSocket 心跳间隔：20 秒，覆盖常见代理的 30-60 秒空闲断连阈值 */
const WS_HEARTBEAT_INTERVAL_MS = 20 * 1000;

/** 所有打开的终端标签，key 为标签 ID */
const tabs = new Map();
/** 当前激活的标签 ID */
let activeTabId = null;
/** 标签序号计数器（用于生成默认标签名） */
let tabSeq = 1;
/** 表单同步暂停标志，避免程序性修改触发 input 事件形成循环 */
let formSyncPaused = false;
/** 已保存会话列表的本地缓存 */
let sessionsCache = [];
/** 会话列表是否已加载完成 */
let sessionsLoaded = false;
/** SFTP 面板是否处于打开状态 */
let sftpPanelOpen = false;
/** 移动端快捷键栏的修饰键锁定状态 */
const stickyMods = { ctrl: false, alt: false, shift: false };
/** 记录键盘偏移，避免重复刷新 */
let lastKeyboardOffset = 0;

// ==================== 工具函数 ====================

/** 将文本或国际化对象转换为当前语言的显示文本 */
function localizeText(text) {
    if (text && typeof text === "object" && text.key) {
        return t(text.key, text.params || {});
    }
    return translateKnownMessage(text);
}

// ==================== 移动端快捷键栏 ====================

const KEYBAR_KEY_MAP = {
    ESC: { data: "\x1b", applyMods: false },
    TAB: { data: "\t", applyMods: false },
    INS: { data: "\x1b[2~", applyMods: false },
    END: { data: "\x1b[F", applyMods: false },
    UP: { data: "\x1b[A", applyMods: false },
    DOWN: { data: "\x1b[B", applyMods: false },
    LEFT: { data: "\x1b[D", applyMods: false },
    RIGHT: { data: "\x1b[C", applyMods: false },
    DASH: { data: "-", applyMods: true },
    COLON: { data: ":", applyMods: true }
};

function toCtrlChar(ch) {
    const upper = ch.toUpperCase();
    const code = upper.charCodeAt(0);
    if (code >= 64 && code <= 95) {
        return String.fromCharCode(code - 64);
    }
    if (upper === "?") {
        return String.fromCharCode(127);
    }
    return null;
}

function applyStickyModifiers(data) {
    if (!stickyMods.ctrl && !stickyMods.alt && !stickyMods.shift) {
        return data;
    }
    if (data.length !== 1) {
        return data;
    }
    const code = data.charCodeAt(0);
    if (code > 127) {
        return data;
    }
    if (code < 32 || code === 127) {
        return data;
    }

    let ch = data;
    if (stickyMods.shift && ch >= "a" && ch <= "z") {
        ch = ch.toUpperCase();
    }
    if (stickyMods.ctrl) {
        const ctrlChar = toCtrlChar(ch);
        if (ctrlChar) {
            ch = ctrlChar;
        }
    }
    if (stickyMods.alt) {
        ch = `\x1b${ch}`;
    }
    return ch;
}

function sendTabInput(tab, data, applyMods = true, showStatusOnFail = false) {
    if (!tab || !tab.connected || !tab.socket || tab.socket.readyState !== WebSocket.OPEN) {
        if (showStatusOnFail) {
            setTabStatus(tab, "请先连接 SSH 再使用快捷键", "error");
        }
        return false;
    }
    const payload = applyMods ? applyStickyModifiers(data) : data;
    tab.socket.send(JSON.stringify({ type: "input", data: payload }));
    return true;
}

function syncStickyModButtons() {
    if (!mobileKeybar) {
        return;
    }
    mobileKeybar.querySelectorAll("[data-mod]").forEach((btn) => {
        const mod = (btn.dataset.mod || "").toLowerCase();
        const active = !!stickyMods[mod];
        btn.classList.toggle("is-active", active);
        btn.setAttribute("aria-pressed", active ? "true" : "false");
    });
}

function toggleStickyMod(mod) {
    if (!(mod in stickyMods)) {
        return;
    }
    stickyMods[mod] = !stickyMods[mod];
    syncStickyModButtons();
}

function setKeybarCollapsed(collapsed) {
    document.body.classList.toggle("keybar-collapsed", collapsed);
    if (mobileKeybar) {
        mobileKeybar.classList.toggle("collapsed", collapsed);
    }
    if (mobileKeybarToggle) {
        mobileKeybarToggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
        mobileKeybarToggle.title = collapsed ? "展开快捷键栏" : "收起快捷键栏";
        mobileKeybarToggle.innerHTML = collapsed ? "&#9650;" : "&#9660;";
    }
    setTimeout(refreshTerminalAfterLayoutChange, 220);
}

function bindMobileKeybar() {
    if (!mobileKeybar) {
        return;
    }
    mobileKeybar.addEventListener("click", (event) => {
        const btn = event.target.closest("button");
        if (!btn || !mobileKeybar.contains(btn)) {
            return;
        }
        const action = btn.dataset.action;
        if (action === "toggleKeybar") {
            setKeybarCollapsed(!document.body.classList.contains("keybar-collapsed"));
            return;
        }
        if (action === "paste") {
            handlePasteAction(activeTab());
            return;
        }
        const mod = btn.dataset.mod;
        if (mod) {
            toggleStickyMod(mod.toLowerCase());
            return;
        }
        const key = btn.dataset.key;
        if (!key || !KEYBAR_KEY_MAP[key]) {
            return;
        }
        const tab = activeTab();
        const { data, applyMods } = KEYBAR_KEY_MAP[key];
        const sent = sendTabInput(tab, data, applyMods, true);
        if (sent && tab) {
            tab.term.focus();
        }
    });
    syncStickyModButtons();
}

function updateKeyboardOffset() {
    const root = document.documentElement;
    if (window.innerWidth > 900) {
        root.style.setProperty("--keyboard-offset", "0px");
        return;
    }
    const viewport = window.visualViewport;
    if (!viewport) {
        root.style.setProperty("--keyboard-offset", "0px");
        return;
    }
    const rawOffset = window.innerHeight - viewport.height - viewport.offsetTop;
    const offset = Math.max(0, Math.round(rawOffset));
    root.style.setProperty("--keyboard-offset", `${offset}px`);
    if (offset !== lastKeyboardOffset) {
        lastKeyboardOffset = offset;
        setTimeout(refreshTerminalAfterLayoutChange, 0);
    }
}

function normalizePasteText(text) {
    if (!text) {
        return "";
    }
    return text.replace(/\r?\n/g, "\r");
}

async function writeClipboardText(text) {
    if (!text) {
        return false;
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        return true;
    }
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "true");
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    textarea.style.left = "-9999px";
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(textarea);
    return ok;
}

async function readClipboardText() {
    if (navigator.clipboard && navigator.clipboard.readText) {
        return navigator.clipboard.readText();
    }
    return "";
}

async function handleCopyAction(tab = activeTab()) {
    if (!tab || !tab.term || typeof tab.term.getSelection !== "function") {
        return;
    }
    const text = tab.term.getSelection();
    if (!text) {
        return;
    }
    try {
        const ok = await writeClipboardText(text);
        if (!ok) {
            throw new Error("clipboard");
        }
    } catch (e) {
        setTabStatus(tab, t("status.clipboardUnavailable"), "error");
    }
}

async function handlePasteAction(tab = activeTab()) {
    if (!tab) {
        return;
    }
    if (!tab.connected || !tab.socket || tab.socket.readyState !== WebSocket.OPEN) {
        setTabStatus(tab, t("status.pasteNeedConnect"), "error");
        return;
    }
    let text = "";
    let clipboardFailed = false;
    try {
        text = await readClipboardText();
    } catch (e) {
        clipboardFailed = true;
    }
    if (!text) {
        const isMobile = window.innerWidth <= 900;
        if (isMobile) {
            const manual = window.prompt(t("prompt.pasteContent"), "");
            if (manual === null) {
                return;
            }
            text = manual;
        }
    }
    if (!text) {
        const msgKey = clipboardFailed ? "status.clipboardUnavailable" : "status.pasteEmpty";
        setTabStatus(tab, t(msgKey), "error");
        return;
    }
    const normalized = normalizePasteText(text);
    sendTabInput(tab, normalized, false, false);
    tab.term.focus();
}

function hideTerminalContextMenu() {
    if (!terminalContextMenu) {
        return;
    }
    terminalContextMenu.classList.remove("show");
    terminalContextMenu.setAttribute("aria-hidden", "true");
}

function showTerminalContextMenu(clientX, clientY) {
    if (!terminalContextMenu || !terminalContainer) {
        return;
    }
    const tab = activeTab();
    if (!tab) {
        return;
    }
    const canCopy = tab.term && typeof tab.term.hasSelection === "function" && tab.term.hasSelection();
    if (terminalContextCopyBtn) {
        terminalContextCopyBtn.disabled = !canCopy;
    }
    terminalContextMenu.classList.add("show");
    terminalContextMenu.setAttribute("aria-hidden", "false");

    const containerRect = terminalContainer.getBoundingClientRect();
    const menuRect = terminalContextMenu.getBoundingClientRect();
    const margin = 6;
    let left = clientX - containerRect.left;
    let top = clientY - containerRect.top;
    if (left + menuRect.width > containerRect.width - margin) {
        left = containerRect.width - menuRect.width - margin;
    }
    if (top + menuRect.height > containerRect.height - margin) {
        top = containerRect.height - menuRect.height - margin;
    }
    left = Math.max(margin, left);
    top = Math.max(margin, top);
    terminalContextMenu.style.left = `${Math.round(left)}px`;
    terminalContextMenu.style.top = `${Math.round(top)}px`;
}

function bindTerminalContextMenu() {
    if (!terminalContextMenu || !terminalContainer) {
        return;
    }
    terminalContainer.addEventListener("contextmenu", (event) => {
        if (!terminalContainer.contains(event.target)) {
            return;
        }
        event.preventDefault();
        showTerminalContextMenu(event.clientX, event.clientY);
    });
    terminalContextMenu.addEventListener("click", (event) => {
        const btn = event.target.closest("button");
        if (!btn || !terminalContextMenu.contains(btn)) {
            return;
        }
        const action = btn.dataset.action;
        if (action === "copy") {
            handleCopyAction(activeTab());
        } else if (action === "paste") {
            handlePasteAction(activeTab());
        }
        hideTerminalContextMenu();
    });
    document.addEventListener("click", (event) => {
        if (!terminalContextMenu.classList.contains("show")) {
            return;
        }
        if (terminalContextMenu.contains(event.target)) {
            return;
        }
        hideTerminalContextMenu();
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            hideTerminalContextMenu();
        }
    });
    window.addEventListener("resize", () => {
        hideTerminalContextMenu();
    });
}

/** 生成唯一标识符：优先使用 crypto.randomUUID()，不支持时回退到时间戳 */
function randomId() {
    if (window.crypto && window.crypto.randomUUID) {
        return window.crypto.randomUUID();
    }
    return `tab-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
}

/** 创建空白的 SSH 会话配置（默认值） */
function makeEmptyProfile() {
    return {
        sessionId: null,
        sessionName: "",
        host: "",
        port: 22,
        username: "",
        authType: "PASSWORD",
        password: "",
        privateKey: "",
        passphrase: "",
        saveCredentials: false,
        hasSavedCredentials: false,
        hostFingerprint: "",
        sftpPath: "."
    };
}

// ==================== 标签管理 ====================

/**
 * 创建一个新的终端标签。
 * 初始化 xterm.js 终端实例、fit 插件、WebSocket 等状态。
 * 注册终端输入事件监听，将用户键盘输入通过 WebSocket 发送到后端。
 */
function makeTab(profile = {}) {
    if (typeof Terminal === "undefined" || !window.FitAddon || !window.FitAddon.FitAddon) {
        throw new Error("终端依赖加载失败（xterm.js / addon-fit）。请刷新页面或检查网络/CDN。");
    }

    const id = randomId();
    const pane = document.createElement("div");
    pane.className = "terminal-pane";
    terminalContainer.appendChild(pane);

    const term = new Terminal({
        cursorBlink: true,
        convertEol: true,
        fontSize: 14,
        theme: getTerminalTheme()
    });
    const fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(pane);

    const tab = {
        id,
        pane,
        term,
        fitAddon,
        socket: null,
        connected: false,
        allowEnterReconnect: false,
        labelIndex: tabSeq++,
        status: { rawText: "待连接", type: "normal" },
        profile: { ...makeEmptyProfile(), ...profile },
        sftpEntries: [],
        sftpState: "idle",
        sftpStateTextRaw: "",
        portForwards: [],
        sftpRefreshTimer: null,
        sftpConnectTimeout: null,
        downloadTask: null,
        uploadTask: null,
        lastShellCwd: null,
        heartbeatTimer: null
    };

    term.onData((data) => {
        sendTabInput(tab, data, true, false);
    });
    term.onKey(({ domEvent }) => {
        if (domEvent.key !== "Enter" || domEvent.repeat || domEvent.isComposing) {
            return;
        }
        if (domEvent.altKey || domEvent.ctrlKey || domEvent.metaKey || domEvent.shiftKey) {
            return;
        }
        if (stickyMods.ctrl || stickyMods.alt || stickyMods.shift) {
            return;
        }
        if (activeTabId !== tab.id || tab.connected || !tab.allowEnterReconnect) {
            return;
        }
        domEvent.preventDefault();
        connectActiveTab();
    });

    tabs.set(id, tab);
    return tab;
}

/** 在移动端（≤900px）自动收起侧边栏，为终端提供更多空间 */
function closeSidebarOnMobile() {
    if (window.innerWidth <= 900) {
        const sidebar = document.querySelector(".sidebar");
        if (sidebar && !sidebar.classList.contains("collapsed")) {
            sidebar.classList.add("collapsed");
            setTimeout(() => {
                window.dispatchEvent(new Event("resize"));
            }, 305);
        }
        const backdrop = document.getElementById("sidebarBackdrop");
        if (backdrop) backdrop.classList.remove("active");
    }
}

/** 获取当前激活的标签对象 */
function activeTab() {
    return activeTabId ? tabs.get(activeTabId) : null;
}

/** 获取标签的显示名称：优先使用会话名，其次使用主机地址，最后使用默认编号 */
function labelForTab(tab) {
    return tab.profile.sessionName || tab.profile.host || t("tabs.defaultName", { index: tab.labelIndex });
}

/** 渲染标签栏 UI：根据 tabs Map 动态生成标签按钮和关闭按钮 */
function renderTabs() {
    tabsEl.innerHTML = "";
    tabs.forEach((tab) => {
        const item = document.createElement("div");
        item.className = `tab${tab.id === activeTabId ? " active" : ""}${tab.connected ? " connected" : ""}`;
        item.dataset.id = tab.id;
        item.innerHTML = `
            <div class="tab-status-dot"></div>
            <span class="tab-label">${escapeHtml(labelForTab(tab))}</span>
            <span class="tab-close" data-close-id="${tab.id}">
                <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </span>
        `;
        item.addEventListener("click", () => switchTab(tab.id));
        item.querySelector(".tab-close").addEventListener("click", (e) => {
            e.stopPropagation();
            closeTab(tab.id);
        });
        tabsEl.appendChild(item);
    });
}

/** 切换到指定标签：显示对应终端面板、同步表单、更新 SFTP 和端口转发面板 */
function switchTab(id) {
    if (!tabs.has(id)) {
        return;
    }
    const prev = activeTab();
    if (prev) {
        prev.pane.classList.remove("active");
    }
    activeTabId = id;
    const tab = activeTab();
    tab.pane.classList.add("active");
    renderTabs();
    closeSidebarOnMobile();
    loadFormFromTab(tab);
    renderStatus(tab);
    renderSftpState(tab);
    updateButtons();
    renderSftpEntries(tab);
    renderForwardList(tab);
    if (sftpPanelOpen && tab.connected && (!tab.sftpEntries || tab.sftpEntries.length === 0)) {
        requestSftpList(tab, tab.profile.sftpPath || ".");
    }
    requestAnimationFrame(() => {
        tab.fitAddon.fit();
        if (tab.connected) {
            sendResize(tab);
        }
        tab.term.focus();
    });
}

/** 切换 SFTP 面板的显示/隐藏状态，动画结束后重算终端尺寸 */
function setSftpPanelVisible(show) {
    sftpPanelOpen = !!show;
    if (sftpEls.panel) {
        sftpEls.panel.classList.toggle("open", sftpPanelOpen);
    }
    if (sftpEls.toggleBtn) {
        sftpEls.toggleBtn.classList.toggle("active", sftpPanelOpen);
    }

    const tab = activeTab();
    renderSftpState(tab);
    if (tab && sftpPanelOpen && tab.connected && (!tab.sftpEntries || tab.sftpEntries.length === 0)) {
        requestSftpList(tab, tab.profile.sftpPath || ".");
    }

    // 等待面板开合动画结束后重算终端尺寸。
    setTimeout(() => {
        const current = activeTab();
        if (!current) {
            return;
        }
        current.fitAddon.fit();
        if (current.connected) {
            sendResize(current);
        }
    }, 260);
}

// ==================== 全屏模式 ====================

/** 跨浏览器获取当前全屏元素 */
function getFullscreenElement() {
    return document.fullscreenElement
        || document.webkitFullscreenElement
        || document.mozFullScreenElement
        || document.msFullscreenElement
        || null;
}

function requestFullscreenSafe(element) {
    if (element.requestFullscreen) {
        return Promise.resolve(element.requestFullscreen());
    }
    if (element.webkitRequestFullscreen) {
        return Promise.resolve(element.webkitRequestFullscreen());
    }
    if (element.mozRequestFullScreen) {
        return Promise.resolve(element.mozRequestFullScreen());
    }
    if (element.msRequestFullscreen) {
        return Promise.resolve(element.msRequestFullscreen());
    }
    return Promise.reject(new Error(t("error.fullscreenUnsupported")));
}

function exitFullscreenSafe() {
    if (document.exitFullscreen) {
        return Promise.resolve(document.exitFullscreen());
    }
    if (document.webkitExitFullscreen) {
        return Promise.resolve(document.webkitExitFullscreen());
    }
    if (document.mozCancelFullScreen) {
        return Promise.resolve(document.mozCancelFullScreen());
    }
    if (document.msExitFullscreen) {
        return Promise.resolve(document.msExitFullscreen());
    }
    return Promise.reject(new Error(t("error.exitFullscreenUnsupported")));
}

function syncFullscreenButton() {
    if (!fullscreenBtn) {
        return;
    }
    const active = !!getFullscreenElement();
    fullscreenBtn.classList.toggle("active", active);
    const spanEl = fullscreenBtn.querySelector("span");
    if (spanEl) {
        spanEl.textContent = active ? t("toolbar.exitFullscreen") : t("toolbar.fullscreen");
    } else {
        fullscreenBtn.textContent = active ? t("toolbar.exitFullscreen") : t("toolbar.fullscreen");
    }
    fullscreenBtn.title = active ? t("toolbar.exitFullscreenTitle") : t("toolbar.enterFullscreenTitle");
}

function refreshTerminalAfterLayoutChange() {
    const tab = activeTab();
    if (!tab) {
        return;
    }
    tab.fitAddon.fit();
    if (tab.connected) {
        sendResize(tab);
    }
}

async function toggleFullscreen() {
    try {
        if (getFullscreenElement()) {
            await exitFullscreenSafe();
        } else {
            await requestFullscreenSafe(document.documentElement);
        }
    } catch (e) {
        setTabStatus(activeTab(), e.message || t("error.toggleFullscreen"), "error");
    } finally {
        syncFullscreenButton();
        setTimeout(refreshTerminalAfterLayoutChange, 120);
    }
}

/**
 * 关闭指定标签：断开 SSH 连接、销毁终端实例、移除 DOM 元素。
 * 如果关闭的是最后一个标签，自动创建新的空白标签。
 */
function closeTab(id) {
    const tab = tabs.get(id);
    if (!tab) {
        return;
    }
    if (tab.sftpRefreshTimer) {
        clearTimeout(tab.sftpRefreshTimer);
        tab.sftpRefreshTimer = null;
    }
    if (tab.sftpConnectTimeout) {
        clearTimeout(tab.sftpConnectTimeout);
        tab.sftpConnectTimeout = null;
    }
    disconnectTab(tab, false);
    tab.term.dispose();
    tab.pane.remove();
    tabs.delete(id);

    if (tabs.size === 0) {
        createAndSwitchTab();
    } else if (activeTabId === id) {
        const first = tabs.keys().next().value;
        switchTab(first);
    } else {
        renderTabs();
    }
}

// ==================== 表单与配置同步 ====================

/** 根据认证类型（PASSWORD/PRIVATE_KEY）切换显示密码输入框或私钥输入框 */
function updateAuthFieldsVisibility() {
    const val = formEls.authType.value;
    const pwdGrp = document.getElementById("passwordGroup");
    const keyGrp = document.getElementById("keyGroup");
    if (pwdGrp) pwdGrp.style.display = (val === "PASSWORD") ? "block" : "none";
    if (keyGrp) keyGrp.style.display = (val === "PRIVATE_KEY") ? "block" : "none";
}

/** 从标签的 profile 数据加载表单（暂停同步以避免触发反向更新） */
function loadFormFromTab(tab) {
    formSyncPaused = true;
    formEls.sessionName.value = tab.profile.sessionName || "";
    formEls.host.value = tab.profile.host || "";
    formEls.port.value = tab.profile.port || 22;
    formEls.username.value = tab.profile.username || "";
    formEls.authType.value = tab.profile.authType || "PASSWORD";
    formEls.password.value = tab.profile.password || "";
    formEls.privateKey.value = tab.profile.privateKey || "";
    formEls.passphrase.value = tab.profile.passphrase || "";
    if (formEls.saveCredentials) {
        formEls.saveCredentials.checked = !!tab.profile.saveCredentials;
    }
    formEls.hostFingerprint.value = tab.profile.hostFingerprint || "";
    if (sftpEls.path) {
        sftpEls.path.value = tab.profile.sftpPath || ".";
    }

    updateAuthFieldsVisibility();
    formSyncPaused = false;
}

/** 将表单当前值同步到当前活跃标签的 profile 中（由 input/change 事件触发） */
function syncFormToActiveTab() {
    if (formSyncPaused) {
        return;
    }
    const tab = activeTab();
    if (!tab) {
        return;
    }
    tab.profile.sessionName = formEls.sessionName.value.trim();
    tab.profile.host = formEls.host.value.trim();
    tab.profile.port = Number(formEls.port.value || 22);
    tab.profile.username = formEls.username.value.trim();
    tab.profile.authType = formEls.authType.value;
    tab.profile.password = formEls.password.value;
    tab.profile.privateKey = formEls.privateKey.value;
    tab.profile.passphrase = formEls.passphrase.value;
    tab.profile.saveCredentials = formEls.saveCredentials ? formEls.saveCredentials.checked : false;
    tab.profile.hostFingerprint = formEls.hostFingerprint.value.trim();
    if (sftpEls.path) {
        tab.profile.sftpPath = sftpEls.path.value.trim() || ".";
    }
    renderTabs();
}

// ==================== 状态显示 ====================

/** 设置标签的状态提示文本（normal/ok/error），显示在终端上方的覆盖层 */
function setTabStatus(tab, text, type = "normal") {
    const rawText = text || "";
    if (!tab) {
        statusOverlay.textContent = localizeText(rawText);
        statusOverlay.className = "status-overlay show";
        if (type === "ok") statusOverlay.classList.add("ok");
        if (type === "error") statusOverlay.classList.add("error");
        return;
    }

    tab.status = { rawText, type };
    if (activeTabId === tab.id) {
        renderStatus(tab);
    }
}

function normalizeSftpState(state) {
    if (state === "loading" || state === "ok" || state === "error") {
        return state;
    }
    return "idle";
}

function defaultSftpStateText(state) {
    if (state === "loading") {
        return t("sftp.state.loading");
    }
    if (state === "ok") {
        return t("sftp.state.ok");
    }
    if (state === "error") {
        return t("sftp.state.error");
    }
    return t("sftp.state.idle");
}

function setSftpState(tab, state, text = "") {
    if (!tab) {
        renderSftpState(null);
        return;
    }
    const normalized = normalizeSftpState(state);
    tab.sftpState = normalized;
    tab.sftpStateTextRaw = text || "";
    if (activeTabId === tab.id) {
        renderSftpState(tab);
    }
}

function renderSftpState(tab = activeTab()) {
    if (!sftpEls.state) {
        return;
    }
    let state = "idle";
    let text = defaultSftpStateText(state);
    if (tab && tab.connected) {
        state = normalizeSftpState(tab.sftpState);
        text = tab.sftpStateTextRaw ? localizeText(tab.sftpStateTextRaw) : defaultSftpStateText(state);
    }
    sftpEls.state.className = `sftp-state sftp-state-${state}`;
    sftpEls.state.textContent = text;
}

function renderStatus(tab) {
    const rawText = tab.status.rawText || "";
    statusOverlay.textContent = localizeText(rawText);
    statusOverlay.className = "status-overlay show";
    if (tab.status.type === "ok") statusOverlay.classList.add("ok");
    if (tab.status.type === "error") statusOverlay.classList.add("error");

    // 默认状态 3 秒自动隐藏；错误状态 5 秒自动隐藏；断线重连提示保持常驻。
    const isReconnectHint = (typeof rawText === "string") && rawText.includes("按 Enter 重连");
    const shouldAutoHide = !isReconnectHint
        && (tab.status.type === "ok"
            || tab.status.type === "error"
            || (tab.status.type === "normal" && !tab.connected));
    if (shouldAutoHide) {
        const delayMs = tab.status.type === "error" ? 5000 : 3000;
        setTimeout(() => {
            if (activeTabId === tab.id && (tab.status.rawText || "") === rawText) {
                statusOverlay.classList.remove("show");
            }
        }, delayMs);
    }
}

/** 根据当前标签的连接状态更新按钮的启用/禁用和显示/隐藏状态 */
function updateButtons() {
    const sftpActionButtons = [
        sftpEls.refreshBtn,
        sftpEls.upBtn,
        sftpEls.mkdirBtn,
        sftpEls.uploadBtn,
        sftpEls.uploadFolderBtn
    ].filter(Boolean);
    const tab = activeTab();
    if (!tab) {
        connectBtn.disabled = true;
        connectBtn.style.display = "block";
        disconnectBtn.style.display = "none";
        saveSessionBtn.disabled = true;
        sftpActionButtons.forEach((btn) => { btn.disabled = true; });
        renderSftpState(null);
        return;
    }
    connectBtn.disabled = tab.connected;
    connectBtn.style.display = tab.connected ? "none" : "block";
    disconnectBtn.disabled = !tab.connected;
    disconnectBtn.style.display = tab.connected ? "block" : "none";
    saveSessionBtn.disabled = false;
    sftpActionButtons.forEach((btn) => { btn.disabled = !tab.connected; });
    [forwardEls.addBtn, forwardEls.refreshBtn]
        .filter(Boolean)
        .forEach((btn) => { btn.disabled = !tab.connected; });
    renderSftpState(tab);
}

// ==================== WebSocket 通信 ====================

/** 根据当前页面协议（http/https）生成 WebSocket URL (ws/wss) */
function wsUrl() {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    return `${protocol}://${window.location.host}/ws/ssh`;
}

function normalizeFingerprint(value) {
    const raw = (value || "").trim();
    if (!raw) {
        return "";
    }
    if (raw.startsWith("SHA256:")) {
        return `SHA256:${raw.substring("SHA256:".length).trim()}`;
    }
    return `SHA256:${raw}`;
}

function reconnectHintStatusText(text) {
    const base = (text || "").trim() || "连接已断开";
    if (base.includes("Enter")) {
        return base;
    }
    return `${base}（按 Enter 重连）`;
}

function validateTabProfile(tab) {
    const p = tab.profile;
    if (!p.host || !p.username) {
        return "host 和 username 不能为空";
    }
    if (p.authType === "PASSWORD" && !p.password) {
        return "密码认证时 password 不能为空";
    }
    if (p.authType === "PRIVATE_KEY" && !p.privateKey.trim()) {
        return "私钥认证时 privateKey 不能为空";
    }
    return null;
}

function snapshotProfileFromForm(tab) {
    tab.profile.sessionName = formEls.sessionName.value.trim();
    tab.profile.host = formEls.host.value.trim();
    tab.profile.port = Number(formEls.port.value || 22);
    tab.profile.username = formEls.username.value.trim();
    tab.profile.authType = formEls.authType.value;
    tab.profile.password = formEls.password.value;
    tab.profile.privateKey = formEls.privateKey.value;
    tab.profile.passphrase = formEls.passphrase.value;
    tab.profile.saveCredentials = formEls.saveCredentials ? formEls.saveCredentials.checked : false;
    tab.profile.hostFingerprint = formEls.hostFingerprint.value.trim();
    if (sftpEls.path) {
        tab.profile.sftpPath = normalizeRemotePath(sftpEls.path.value);
    }
    return tab.profile;
}

function isSocketReady(tab) {
    return !!(tab && tab.connected && tab.socket && tab.socket.readyState === WebSocket.OPEN);
}

function sendWs(tab, payload) {
    if (!tab || !tab.socket || tab.socket.readyState !== WebSocket.OPEN) {
        return false;
    }
    tab.socket.send(JSON.stringify(payload));
    return true;
}

function stopHeartbeat(tab) {
    if (!tab || !tab.heartbeatTimer) {
        return;
    }
    clearInterval(tab.heartbeatTimer);
    tab.heartbeatTimer = null;
}

function startHeartbeat(tab) {
    stopHeartbeat(tab);
    if (!tab) {
        return;
    }
    tab.heartbeatTimer = setInterval(() => {
        if (!tab.socket || tab.socket.readyState !== WebSocket.OPEN) {
            stopHeartbeat(tab);
            return;
        }
        try {
            sendWs(tab, { type: "ping" });
        } catch (e) {
            console.warn("WebSocket 心跳发送失败", e);
        }
    }, WS_HEARTBEAT_INTERVAL_MS);
}

function normalizeRemotePath(path) {
    const raw = (path || "").trim();
    if (!raw) {
        return ".";
    }
    return raw;
}

function joinRemotePath(dir, fileName) {
    const base = normalizeRemotePath(dir);
    if (base === ".") {
        return fileName;
    }
    if (base === "/") {
        return `/${fileName}`;
    }
    return base.endsWith("/") ? `${base}${fileName}` : `${base}/${fileName}`;
}

function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes < 0) {
        return "-";
    }
    if (bytes < 1024) {
        return `${bytes}B`;
    }
    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)}KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

function formatTimestamp(ts) {
    if (!Number.isFinite(ts) || ts <= 0) {
        return "-";
    }
    return new Date(ts).toLocaleString(getLanguage());
}

function parentRemotePath(path) {
    const normalized = normalizeRemotePath(path);
    if (normalized === "/" || normalized === ".") {
        return normalized;
    }
    const trimmed = normalized.endsWith("/") ? normalized.slice(0, -1) : normalized;
    const idx = trimmed.lastIndexOf("/");
    if (idx < 0) {
        return ".";
    }
    if (idx === 0) {
        return "/";
    }
    return trimmed.slice(0, idx);
}

function permissionAndSize(entry) {
    const perms = entry.permissions || "-";
    if (entry.directory) {
        return perms;
    }
    return `${perms} / ${formatBytes(Number(entry.size || 0))}`;
}

function renderSftpEntries(tab) {
    if (!sftpEls.entries) {
        return;
    }
    const current = activeTab();
    if (!tab) {
        if (!current) {
            sftpEls.entries.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.sftpAfterConnect"))}</div>`;
        }
        return;
    }
    if (!current || current.id !== tab.id) {
        return;
    }
    if (!tab || !tab.connected) {
        sftpEls.entries.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.sftpAfterConnect"))}</div>`;
        return;
    }
    const entries = Array.isArray(tab.sftpEntries) ? [...tab.sftpEntries] : [];
    if (tab.sftpState === "loading" && entries.length === 0) {
        sftpEls.entries.innerHTML = `
            <div class="result-empty sftp-loading">
                <span class="sftp-loading-indicator"></span>
                <span>${escapeHtml(t("sftp.state.loading"))}</span>
            </div>
        `;
        return;
    }
    entries.sort((a, b) => {
        const aDir = a.directory ? 0 : 1;
        const bDir = b.directory ? 0 : 1;
        if (aDir !== bDir) {
            return aDir - bDir;
        }
        return String(a.name || "").localeCompare(String(b.name || ""), getLanguage());
    });
    const path = tab.profile.sftpPath || ".";
    const parentPath = parentRemotePath(path);
    const rows = [];

    if (path !== "/" && path !== ".") {
        rows.push(`
            <div class="sftp-entry sftp-entry-dir" data-open-path="${escapeHtml(parentPath)}">
                <div class="sftp-name-cell">
                    <span class="sftp-icon">DIR</span>
                    <span class="sftp-name">..</span>
                </div>
                <span class="sftp-date empty"></span>
                <span class="sftp-meta empty"></span>
                <span class="sftp-actions empty"></span>
            </div>
        `);
    }

    entries.forEach((entry) => {
        const safePath = escapeHtml(entry.path || "");
        const safeName = escapeHtml(entry.name || "");
        const modified = Number(entry.modifiedAt || 0) > 0 ? formatTimestamp(Number(entry.modifiedAt || 0)) : "-";
        const perms = permissionAndSize(entry);
        const rowClass = entry.directory ? "sftp-entry sftp-entry-dir" : "sftp-entry";
        rows.push(`
            <div class="${rowClass}" ${entry.directory ? `data-open-path="${safePath}"` : ""}>
                <div class="sftp-name-cell">
                    <span class="sftp-icon">${entry.directory ? "DIR" : "FILE"}</span>
                    <span class="sftp-name">${safeName}</span>
                </div>
                <span class="sftp-date${modified === "-" ? " empty" : ""}">${modified === "-" ? "" : escapeHtml(modified)}</span>
                <span class="sftp-meta${perms === "-" ? " empty" : ""}">${perms === "-" ? "" : escapeHtml(perms)}</span>
                <span class="sftp-actions${entry.directory ? " empty" : ""}">
                    ${entry.directory ? ""
                : `<button class="mini-btn sftp-download-btn" type="button" data-download-path="${safePath}">${escapeHtml(t("common.download"))}</button>`
            }
                </span>
            </div>
        `);
    });

    if (rows.length === 0) {
        sftpEls.entries.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.currentDirectory"))}</div>`;
        return;
    }

    sftpEls.entries.innerHTML = rows.join("");
    sftpEls.entries.querySelectorAll("[data-open-path]").forEach((el) => {
        el.addEventListener("click", () => {
            const current = activeTab();
            if (!current || current.id !== tab.id) {
                return;
            }
            const nextPath = el.dataset.openPath || ".";
            requestSftpList(current, nextPath);
        });
    });
    sftpEls.entries.querySelectorAll(".sftp-download-btn").forEach((btn) => {
        btn.addEventListener("click", (event) => {
            event.stopPropagation();
            const current = activeTab();
            if (!current || current.id !== tab.id) {
                return;
            }
            requestSftpDownload(current, btn.dataset.downloadPath || "");
        });
    });
}

function renderForwardList(tab) {
    if (!forwardEls.list) {
        return;
    }
    const current = activeTab();
    if (!tab) {
        if (!current) {
            forwardEls.list.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.forwardAfterConnect"))}</div>`;
        }
        return;
    }
    if (!current || current.id !== tab.id) {
        return;
    }
    if (!tab || !tab.connected) {
        forwardEls.list.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.forwardAfterConnect"))}</div>`;
        return;
    }
    if (!tab.portForwards || tab.portForwards.length === 0) {
        forwardEls.list.innerHTML = `<div class="result-empty">${escapeHtml(t("empty.noForwards"))}</div>`;
        return;
    }

    const html = tab.portForwards.map((item) => {
        const direction = (item.direction || "LOCAL").toUpperCase();
        const safeDirection = escapeHtml(t(direction === "REMOTE" ? "forward.direction.remote" : "forward.direction.local"));
        const safeBindHost = escapeHtml(item.bindHost || "127.0.0.1");
        const safeTargetHost = escapeHtml(item.targetHost || "");
        const bindPort = Number(item.bindPort || 0);
        const targetPort = Number(item.targetPort || 0);
        return `
            <div class="result-item">
                <div class="result-item-main">
                    <div class="result-name">${safeDirection} ${safeBindHost}:${bindPort}</div>
                    <div class="result-meta">→ ${safeTargetHost}:${targetPort}</div>
                </div>
                <div class="result-actions">
                    <button class="mini-btn forward-del-btn"
                            data-direction="${escapeHtml(direction)}"
                            data-bind-host="${safeBindHost}"
                            data-bind-port="${bindPort}"
                            type="button">${escapeHtml(t("common.delete"))}</button>
                </div>
            </div>
        `;
    }).join("");
    forwardEls.list.innerHTML = html;

    forwardEls.list.querySelectorAll(".forward-del-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            const current = activeTab();
            if (!current || current.id !== tab.id) {
                return;
            }
            removeForward(current, {
                direction: btn.dataset.direction || "LOCAL",
                bindHost: btn.dataset.bindHost || "127.0.0.1",
                bindPort: Number(btn.dataset.bindPort || 0)
            });
        });
    });
}

// ==================== SFTP 操作 ====================

/** 请求后端列出指定远端目录的文件列表 */
function requestSftpList(tab, path = null) {
    if (!isSocketReady(tab)) {
        setSftpState(tab, "idle");
        setTabStatus(tab, "请先连接 SSH 再使用 SFTP", "error");
        return;
    }
    const targetPath = normalizeRemotePath(path ?? (sftpEls.path ? sftpEls.path.value : "."));
    tab.profile.sftpPath = targetPath;
    if (sftpEls.path) {
        sftpEls.path.value = targetPath;
    }
    const sent = sendWs(tab, {
        type: "sftp_list",
        path: targetPath
    });
    if (!sent) {
        setSftpState(tab, "error", "连接失败");
        setTabStatus(tab, "SFTP 请求发送失败", "error");
        return;
    }
    setSftpState(tab, "loading", "连接中...");
    if (tab.sftpConnectTimeout) {
        clearTimeout(tab.sftpConnectTimeout);
    }
    tab.sftpConnectTimeout = setTimeout(() => {
        if (tab.sftpState === "loading") {
            setSftpState(tab, "error", "SFTP 连接超时，服务器可能不支持 SFTP");
            renderSftpEntries(tab);
        }
        tab.sftpConnectTimeout = null;
    }, 15000);
    if (activeTabId === tab.id) {
        renderSftpEntries(tab);
    }
}

/** 请求后端下载指定远端文件（分块流式传输） */
function requestSftpDownload(tab, path) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再下载文件", "error");
        return;
    }
    const targetPath = normalizeRemotePath(path);
    if (!targetPath) {
        setTabStatus(tab, "下载路径不能为空", "error");
        return;
    }
    tab.downloadTask = null;
    setTabStatus(tab, { key: "status.preparingDownload", params: { path: targetPath } });
    const sent = sendWs(tab, {
        type: "sftp_download",
        path: targetPath
    });
    if (!sent) {
        setTabStatus(tab, "下载请求发送失败: WebSocket 不可用", "error");
        return;
    }
}

function beginDownloadTask(tab, path, totalBytes) {
    tab.downloadTask = {
        path,
        totalBytes: Number(totalBytes || 0),
        sentBytes: 0,
        chunks: [],
        lastProgressAt: 0,
        lastProgressPercent: -1,
        downloadId: null
    };
}

function beginUploadTask(tab, totalFiles, totalBytes) {
    tab.uploadTask = {
        totalFiles: Number(totalFiles || 0),
        totalBytes: Number(totalBytes || 0),
        sentFiles: 0,
        sentBytes: 0,
        currentFile: "",
        lastProgressAt: 0,
        lastProgressPercent: -1
    };
    updateUploadProgressStatus(tab, true);
}

function updateUploadProgressStatus(tab, force = false) {
    const task = tab.uploadTask;
    if (!task) {
        return;
    }

    const now = Date.now();
    if (!force && now - task.lastProgressAt < 120) {
        return;
    }
    task.lastProgressAt = now;

    let percent = 0;
    if (task.totalBytes > 0) {
        percent = Math.min(100, Math.floor((task.sentBytes * 100) / task.totalBytes));
    } else if (task.totalFiles > 0) {
        percent = Math.min(100, Math.floor((task.sentFiles * 100) / task.totalFiles));
    }
    if (!force && percent === task.lastProgressPercent) {
        return;
    }
    task.lastProgressPercent = percent;

    const filePart = t("progress.files", {
        current: task.sentFiles,
        total: task.totalFiles
    });
    const fileNamePart = task.currentFile ? t("progress.currentFile", { name: task.currentFile }) : "";
    if (task.totalBytes > 0) {
        setTabStatus(
            tab,
            {
                key: "status.uploadProgressWithBytes",
                params: {
                    percent,
                    sent: formatBytes(task.sentBytes),
                    total: formatBytes(task.totalBytes),
                    filePart,
                    fileNamePart
                }
            }
        );
        return;
    }
    setTabStatus(tab, {
        key: "status.uploadProgress",
        params: {
            percent,
            filePart,
            fileNamePart
        }
    });
}

function updateDownloadProgressStatus(tab, force = false) {
    const task = tab.downloadTask;
    if (!task) {
        return;
    }

    const now = Date.now();
    if (!force && now - task.lastProgressAt < 120) {
        return;
    }
    task.lastProgressAt = now;

    if (task.totalBytes > 0) {
        const percent = Math.min(100, Math.floor((task.sentBytes * 100) / task.totalBytes));
        if (!force && percent === task.lastProgressPercent) {
            return;
        }
        task.lastProgressPercent = percent;
        setTabStatus(
            tab,
            {
                key: "status.downloadProgressWithBytes",
                params: {
                    percent,
                    sent: formatBytes(task.sentBytes),
                    total: formatBytes(task.totalBytes)
                }
            }
        );
        return;
    }

    setTabStatus(tab, {
        key: "status.downloadProgressBytes",
        params: {
            sent: formatBytes(task.sentBytes)
        }
    });
}

function appendDownloadChunk(tab, msg) {
    if (!tab.downloadTask) {
        beginDownloadTask(tab, msg.path || "download.bin", Number(msg.totalBytes || 0));
        tab.downloadTask.downloadId = msg.downloadId || null;
    }

    const task = tab.downloadTask;
    if (task.downloadId && msg.downloadId && task.downloadId !== msg.downloadId) {
        return;
    }
    if (!task.downloadId && msg.downloadId) {
        task.downloadId = msg.downloadId;
    }
    const chunkData = msg.data || "";
    if (chunkData) {
        const bytes = base64ToBytes(chunkData);
        task.chunks.push(bytes);
    }

    task.sentBytes = Number(msg.sentBytes || task.sentBytes);
    if (task.downloadId && Number.isFinite(Number(msg.chunkIndex))) {
        sendWs(tab, {
            type: "sftp_download_ack",
            downloadId: task.downloadId,
            chunkIndex: Number(msg.chunkIndex)
        });
    }
    updateDownloadProgressStatus(tab, !!msg.last);
    if (!msg.last) {
        return;
    }

    const blob = new Blob(task.chunks);
    triggerFileDownloadBlob(task.path || "download.bin", blob);
    setTabStatus(tab, {
        key: "status.downloadComplete",
        params: {
            size: formatBytes(blob.size)
        }
    }, "ok");
    tab.downloadTask = null;
}

/**
 * 上传单个文件到远端：分片读取本地文件，逐块通过 WebSocket 发送。
 * 通过监控 WebSocket.bufferedAmount 实现简易背压，防止大文件上传时内存溢出。
 */
async function uploadSingleFile(tab, file, remotePath, onProgress = null) {
    const uploadId = randomId();
    const started = sendWs(tab, {
        type: "sftp_upload_start",
        uploadId,
        path: remotePath,
        overwrite: true
    });
    if (!started) {
        throw new Error("上传开始失败: WebSocket 不可用");
    }
    if (typeof onProgress === "function") {
        onProgress(0, file.size, false);
    }

    if (file.size === 0) {
        sendWs(tab, {
            type: "sftp_upload_chunk",
            uploadId,
            data: "",
            last: true
        });
        if (typeof onProgress === "function") {
            onProgress(0, 0, true);
        }
        return;
    }

    let offset = 0;
    while (offset < file.size) {
        if (!isSocketReady(tab)) {
            throw new Error("上传中断: WebSocket 已关闭");
        }
        const end = Math.min(offset + SFTP_UPLOAD_CHUNK_BYTES, file.size);
        const chunk = new Uint8Array(await file.slice(offset, end).arrayBuffer());

        // 通过 bufferedAmount 做简易背压，避免大文件导致浏览器内存飙升。
        while (tab.socket && tab.socket.bufferedAmount > SFTP_UPLOAD_MAX_BUFFERED_BYTES) {
            await new Promise((resolve) => { setTimeout(resolve, 20); });
        }

        const sent = sendWs(tab, {
            type: "sftp_upload_chunk",
            uploadId,
            data: bytesToBase64(chunk),
            last: end >= file.size
        });
        if (!sent) {
            throw new Error("上传中断: WebSocket 发送失败");
        }
        offset = end;
        if (typeof onProgress === "function") {
            onProgress(offset, file.size, end >= file.size);
        }
    }
}

/** 批量上传文件（或整个文件夹），依次分片上传每个文件，显示整体进度 */
async function requestSftpUpload(tab, files, isFolder = false) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再上传文件", "error");
        return;
    }
    if (!files || files.length === 0) {
        setTabStatus(tab, "未选择待上传文件", "error");
        return;
    }
    const currentPath = normalizeRemotePath((sftpEls.path ? sftpEls.path.value : tab.profile.sftpPath) || ".");
    const totalBytes = files.reduce((sum, file) => sum + Number(file.size || 0), 0);
    beginUploadTask(tab, files.length, totalBytes);

    // 让浏览器先渲染“上传中”状态，再进入分片循环。
    await new Promise((resolve) => { setTimeout(resolve, 0); });

    let sent = 0;
    let committedBytes = 0;
    try {
        for (const file of files) {
            const relative = isFolder
                ? (file.webkitRelativePath || file.name).replaceAll("\\", "/")
                : file.name;
            const remotePath = joinRemotePath(currentPath, relative);
            const fileBaseBytes = committedBytes;
            if (tab.uploadTask) {
                tab.uploadTask.currentFile = relative;
                tab.uploadTask.sentFiles = sent;
                tab.uploadTask.sentBytes = fileBaseBytes;
                updateUploadProgressStatus(tab, true);
            }
            await uploadSingleFile(tab, file, remotePath, (fileSentBytes, _fileTotalBytes, done) => {
                if (!tab.uploadTask) {
                    return;
                }
                tab.uploadTask.currentFile = relative;
                tab.uploadTask.sentBytes = fileBaseBytes + fileSentBytes;
                tab.uploadTask.sentFiles = sent + (done ? 1 : 0);
                updateUploadProgressStatus(tab, done);
            });
            sent += 1;
            committedBytes += Number(file.size || 0);
            if (tab.uploadTask) {
                tab.uploadTask.sentFiles = sent;
                tab.uploadTask.sentBytes = committedBytes;
                updateUploadProgressStatus(tab, true);
            }
        }
    } catch (e) {
        tab.uploadTask = null;
        throw e;
    }
    tab.uploadTask = null;
    setTabStatus(tab, { key: "status.uploadSubmitted", params: { count: sent } }, "ok");
}

/** 弹出对话框让用户输入目录名，然后在远端当前目录下创建新目录 */
function requestSftpMkdir(tab) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再创建目录", "error");
        return;
    }
    const dirName = window.prompt(t("prompt.enterDirectoryName"));
    if (!dirName || !dirName.trim()) {
        return;
    }
    if (dirName.includes("/")) {
        setTabStatus(tab, "目录名不能包含 /", "error");
        return;
    }
    const path = joinRemotePath(tab.profile.sftpPath || ".", dirName.trim());
    sendWs(tab, {
        type: "sftp_mkdir",
        path
    });
    setTabStatus(tab, { key: "status.creatingDirectory", params: { path } });
}

// ==================== 端口转发操作 ====================

/** 请求后端返回当前活跃的端口转发列表 */
function requestForwardList(tab) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再查看端口转发", "error");
        return;
    }
    sendWs(tab, { type: "port_forward_list" });
}

/** 从表单收集参数，请求后端创建新的端口转发规则 */
function addForward(tab) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再新增端口转发", "error");
        return;
    }
    const direction = (forwardEls.direction.value || "LOCAL").toUpperCase();
    const bindHost = (forwardEls.bindHost.value || "127.0.0.1").trim() || "127.0.0.1";
    const bindPort = Number(forwardEls.bindPort.value || 0);
    const targetHost = (forwardEls.targetHost.value || "").trim();
    const targetPort = Number(forwardEls.targetPort.value || 0);

    if (!targetHost || targetPort <= 0 || targetPort > 65535) {
        setTabStatus(tab, "请填写合法的目标地址和端口", "error");
        return;
    }
    if (bindPort < 0 || bindPort > 65535) {
        setTabStatus(tab, "监听端口必须在 0-65535 之间", "error");
        return;
    }

    sendWs(tab, {
        type: "port_forward_add",
        direction,
        bindHost,
        bindPort,
        targetHost,
        targetPort
    });
}

/** 请求后端删除指定的端口转发规则 */
function removeForward(tab, item) {
    if (!isSocketReady(tab)) {
        setTabStatus(tab, "请先连接 SSH 再删除端口转发", "error");
        return;
    }
    sendWs(tab, {
        type: "port_forward_remove",
        direction: (item.direction || "LOCAL").toUpperCase(),
        bindHost: item.bindHost || "127.0.0.1",
        bindPort: Number(item.bindPort || 0)
    });
}

/**
 * 连接当前活跃标签 —— 建立 WebSocket 连接并发送 SSH connect 消息。
 *
 * WebSocket 消息处理流程：
 * - connected → 更新连接状态、请求 SFTP 列表和端口转发列表
 * - output → Base64 解码后写入终端（SSH 输出）
 * - cwd → 更新 SFTP 面板路径（Shell 工作目录变化）
 * - sftp_list → 更新文件列表
 * - sftp_download_start/chunk → 分块下载文件
 * - sftp_upload → 上传完成通知
 * - port_forward_list → 更新端口转发列表
 * - error → 显示错误提示
 * - disconnected → 清理连接状态
 */
function connectActiveTab() {
    const tab = activeTab();
    if (!tab || tab.connected) {
        return;
    }
    const profile = snapshotProfileFromForm(tab);

    const invalid = validateTabProfile(tab);
    if (invalid) {
        setTabStatus(tab, invalid, "error");
        return;
    }
    tab.allowEnterReconnect = false;

    if (tab.socket) {
        safeCloseSocket(tab);
    }

    tab.term.clear();
    tab.sftpEntries = [];
    setSftpState(tab, "idle");
    tab.portForwards = [];
    tab.uploadTask = null;
    tab.lastShellCwd = null;
    renderSftpEntries(tab);
    renderForwardList(tab);
    setTabStatus(tab, "正在建立 SSH 连接...");
    const socket = new WebSocket(wsUrl());
    tab.socket = socket;
    const isCurrentSocket = () => tab.socket === socket;

    socket.onopen = () => {
        if (!isCurrentSocket()) {
            return;
        }
        startHeartbeat(tab);
        tab.fitAddon.fit();
        try {
            socket.send(JSON.stringify({
                type: "connect",
                host: profile.host,
                port: profile.port,
                username: profile.username,
                authType: profile.authType,
                password: profile.password,
                privateKey: profile.privateKey,
                passphrase: profile.passphrase,
                hostFingerprint: normalizeFingerprint(profile.hostFingerprint),
                cols: tab.term.cols,
                rows: tab.term.rows
            }));
        } catch (e) {
            setTabStatus(tab, e.message || "连接请求发送失败", "error");
            safeCloseSocket(tab);
        }
    };

    socket.onmessage = (event) => {
        try {
            if (!isCurrentSocket()) {
                return;
            }

            let msg;
            try {
                msg = JSON.parse(event.data);
            } catch (e) {
                setTabStatus(tab, "收到未知消息", "error");
                return;
            }

            if (msg.type === "cwd") {
                const shellCwd = normalizeRemotePath(msg.path || ".");
                const changed = shellCwd !== tab.lastShellCwd;
                tab.lastShellCwd = shellCwd;
                if (!tab.connected) {
                    tab.profile.sftpPath = shellCwd;
                    if (activeTabId === tab.id && sftpEls.path) {
                        sftpEls.path.value = shellCwd;
                    }
                    return;
                }
                if (!changed) {
                    return;
                }
                tab.profile.sftpPath = shellCwd;
                if (activeTabId === tab.id && sftpEls.path) {
                    sftpEls.path.value = shellCwd;
                }
                if (sftpPanelOpen) {
                    requestSftpList(tab, shellCwd);
                }
                return;
            }

            if (msg.type === "connected") {
                tab.connected = true;
                tab.allowEnterReconnect = false;
                if (msg.hostFingerprint) {
                    tab.profile.hostFingerprint = msg.hostFingerprint;
                    if (activeTabId === tab.id) {
                        formEls.hostFingerprint.value = msg.hostFingerprint;
                    }
                }
                setTabStatus(tab, msg.message || "SSH 连接成功", "ok");
                updateButtons();
                renderTabs();
                tab.term.focus();
                requestForwardList(tab);
                const initialSftpPath = normalizeRemotePath(tab.lastShellCwd || msg.sftpPath || ".");
                tab.profile.sftpPath = initialSftpPath;
                if (activeTabId === tab.id && sftpEls.path) {
                    sftpEls.path.value = initialSftpPath;
                }
                requestSftpList(tab, initialSftpPath);
                return;
            }

            if (msg.type === "output") {
                tab.term.write(base64ToText(msg.data || ""));
                return;
            }

            if (msg.type === "pong") {
                return;
            }

            if (msg.type === "hostkey_required") {
                tab.connected = false;
                setSftpState(tab, "idle");
                tab.profile.hostFingerprint = msg.hostFingerprint || "";
                if (activeTabId === tab.id) {
                    formEls.hostFingerprint.value = tab.profile.hostFingerprint;
                }
                setTabStatus(tab, {
                    key: "status.hostkeyRequired",
                    params: {
                        message: localizeText(msg.message || "需要主机指纹确认"),
                        fingerprint: msg.hostFingerprint || "-"
                    }
                }, "error");
                updateButtons();
                renderTabs();
                safeCloseSocket(tab);
                return;
            }

            if (msg.type === "error") {
                tab.downloadTask = null;
                tab.uploadTask = null;
                if (tab.sftpState === "loading") {
                    if (tab.sftpConnectTimeout) {
                        clearTimeout(tab.sftpConnectTimeout);
                        tab.sftpConnectTimeout = null;
                    }
                    setSftpState(tab, "error", msg.message || "连接失败");
                    renderSftpEntries(tab);
                }
                setTabStatus(tab, msg.message || "发生错误", "error");
                return;
            }

            if (msg.type === "sftp_list") {
                if (tab.sftpConnectTimeout) {
                    clearTimeout(tab.sftpConnectTimeout);
                    tab.sftpConnectTimeout = null;
                }
                tab.sftpEntries = Array.isArray(msg.entries) ? msg.entries : [];
                setSftpState(tab, "ok", "已连接");
                tab.profile.sftpPath = msg.path || tab.profile.sftpPath || ".";
                if (activeTabId === tab.id && sftpEls.path) {
                    sftpEls.path.value = tab.profile.sftpPath;
                }
                renderSftpEntries(tab);
                return;
            }

            if (msg.type === "sftp_upload") {
                if (!tab.uploadTask) {
                    setTabStatus(tab, msg.message || "上传成功", "ok");
                }
                if (sftpEls.uploadFileInput) {
                    sftpEls.uploadFileInput.value = "";
                }
                if (tab.sftpRefreshTimer) {
                    clearTimeout(tab.sftpRefreshTimer);
                }
                tab.sftpRefreshTimer = setTimeout(() => {
                    requestSftpList(tab, tab.profile.sftpPath || ".");
                }, 240);
                return;
            }

            if (msg.type === "sftp_download") {
                triggerFileDownload(msg.path || "download.bin", msg.data || "");
                setTabStatus(tab, {
                    key: "status.downloadComplete",
                    params: {
                        size: formatBytes(Number(msg.size || 0))
                    }
                }, "ok");
                return;
            }

            if (msg.type === "sftp_download_start") {
                beginDownloadTask(tab, msg.path || "download.bin", Number(msg.size || 0));
                if (tab.downloadTask) {
                    tab.downloadTask.downloadId = msg.downloadId || null;
                }
                setTabStatus(tab, msg.message || {
                    key: "status.downloadStarted",
                    params: {
                        path: msg.path || "-"
                    }
                });
                return;
            }

            if (msg.type === "sftp_download_chunk") {
                appendDownloadChunk(tab, msg);
                return;
            }

            if (msg.type === "sftp_mkdir") {
                setTabStatus(tab, msg.message || "目录创建成功", "ok");
                requestSftpList(tab, tab.profile.sftpPath || ".");
                return;
            }

            if (msg.type === "port_forward_list") {
                tab.portForwards = Array.isArray(msg.forwards) ? msg.forwards : [];
                renderForwardList(tab);
                if (msg.message) {
                    setTabStatus(tab, msg.message, "ok");
                }
                return;
            }

            if (msg.type === "disconnected") {
                const wasConnected = tab.connected;
                tab.connected = false;
                tab.allowEnterReconnect = wasConnected;
                tab.lastShellCwd = null;
                setSftpState(tab, "idle");
                tab.sftpEntries = [];
                tab.portForwards = [];
                tab.downloadTask = null;
                tab.uploadTask = null;
                if (tab.sftpRefreshTimer) {
                    clearTimeout(tab.sftpRefreshTimer);
                    tab.sftpRefreshTimer = null;
                }
                if (tab.sftpConnectTimeout) {
                    clearTimeout(tab.sftpConnectTimeout);
                    tab.sftpConnectTimeout = null;
                }
                const disconnectedText = msg.message || "连接已断开";
                setTabStatus(tab, wasConnected ? reconnectHintStatusText(disconnectedText) : disconnectedText);
                updateButtons();
                renderTabs();
                renderSftpEntries(tab);
                renderForwardList(tab);
                safeCloseSocket(tab);
                return;
            }

            if (msg.type === "info") {
                setTabStatus(tab, msg.message || "");
            }
        } catch (e) {
            console.error("处理 WebSocket 消息失败", e);
            setTabStatus(tab, e.message || "处理消息时发生异常", "error");
        }
    };

    socket.onclose = () => {
        if (!isCurrentSocket()) {
            return;
        }
        stopHeartbeat(tab);
        const wasConnected = tab.connected;
        tab.connected = false;
        tab.allowEnterReconnect = wasConnected;
        tab.lastShellCwd = null;
        setSftpState(tab, "idle");
        tab.socket = null;
        tab.sftpEntries = [];
        tab.portForwards = [];
        tab.downloadTask = null;
        tab.uploadTask = null;
        if (tab.sftpRefreshTimer) {
            clearTimeout(tab.sftpRefreshTimer);
            tab.sftpRefreshTimer = null;
        }
        if (tab.sftpConnectTimeout) {
            clearTimeout(tab.sftpConnectTimeout);
            tab.sftpConnectTimeout = null;
        }
        if (wasConnected) {
            setTabStatus(tab, reconnectHintStatusText("WebSocket 已关闭"));
        }
        updateButtons();
        renderTabs();
        renderSftpEntries(tab);
        renderForwardList(tab);
    };

    socket.onerror = () => {
        if (!isCurrentSocket()) {
            return;
        }
        if (tab.sftpState === "loading") {
            setSftpState(tab, "error", "连接失败");
            renderSftpEntries(tab);
        }
        setTabStatus(tab, "WebSocket 连接异常", "error");
    };
}

function safeCloseSocket(tab) {
    if (!tab) {
        return;
    }
    stopHeartbeat(tab);
    if (!tab.socket) {
        return;
    }
    try {
        tab.socket.close();
    } catch (e) {
        console.warn(e);
    }
    tab.socket = null;
}

/** 断开指定标签的 SSH 连接：发送 disconnect 消息、关闭 WebSocket、清理状态 */
function disconnectTab(tab, updateUi = true) {
    if (!tab) {
        return;
    }
    if (tab.socket && tab.socket.readyState === WebSocket.OPEN) {
        try {
            tab.socket.send(JSON.stringify({ type: "disconnect" }));
        } catch (e) {
            console.warn(e);
        }
    }
    safeCloseSocket(tab);
    tab.connected = false;
    tab.allowEnterReconnect = true;
    tab.lastShellCwd = null;
    setSftpState(tab, "idle");
    tab.sftpEntries = [];
    tab.portForwards = [];
    tab.downloadTask = null;
    tab.uploadTask = null;
    if (tab.sftpRefreshTimer) {
        clearTimeout(tab.sftpRefreshTimer);
        tab.sftpRefreshTimer = null;
    }
    if (tab.sftpConnectTimeout) {
        clearTimeout(tab.sftpConnectTimeout);
        tab.sftpConnectTimeout = null;
    }
    setTabStatus(tab, reconnectHintStatusText("已主动断开连接"));
    renderTabs();
    renderSftpEntries(tab);
    renderForwardList(tab);
    if (updateUi) {
        updateButtons();
    }
}

function disconnectActiveTab() {
    const tab = activeTab();
    if (!tab) {
        return;
    }
    disconnectTab(tab);
}

/** 将终端的列数和行数发送到后端，同步远端伪终端尺寸 */
function sendResize(tab) {
    if (!tab || !tab.connected || !tab.socket || tab.socket.readyState !== WebSocket.OPEN) {
        return;
    }
    tab.socket.send(JSON.stringify({
        type: "resize",
        cols: tab.term.cols,
        rows: tab.term.rows
    }));
}

// ==================== 编码/解码工具 ====================

/** 将 Uint8Array 转换为 Base64 字符串（分块处理避免大数组导致栈溢出） */
function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
        const chunk = bytes.subarray(i, i + chunkSize);
        binary += String.fromCharCode(...chunk);
    }
    return btoa(binary);
}

/** 将 Base64 字符串解码为 Uint8Array */
function base64ToBytes(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

/** 通过创建临时 &lt;a&gt; 标签触发浏览器文件下载（Base64 数据） */
function triggerFileDownload(remotePath, base64Data) {
    const bytes = base64ToBytes(base64Data);
    const blob = new Blob([bytes]);
    triggerFileDownloadBlob(remotePath, blob);
}

/** 通过创建临时 &lt;a&gt; 标签触发浏览器文件下载（Blob 数据） */
function triggerFileDownloadBlob(remotePath, blob) {
    const fileName = (remotePath || "download.bin").split("/").filter(Boolean).pop() || "download.bin";
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
}

function base64ToText(base64) {
    const bytes = base64ToBytes(base64);
    return new TextDecoder().decode(bytes);
}

// ==================== REST API 客户端 ====================

/**
 * 封装的 fetch 请求函数：自动设置 Content-Type、处理 401 跳转登录页、统一错误处理。
 */
async function apiFetch(url, options = {}) {
    const res = await fetch(url, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...(options.headers || {})
        }
    });

    if (res.status === 401) {
        window.location.href = "/login";
        return null;
    }

    if (!res.ok) {
        let msg = `请求失败: ${res.status}`;
        try {
            const errBody = await res.json();
            if (errBody && errBody.message) {
                msg = errBody.message;
            }
        } catch (e) {
            // ignore parse error
        }
        throw new Error(msg);
    }

    if (res.status === 204) {
        return null;
    }
    return res.json();
}

/** 从 /api/auth/me 加载当前登录用户信息，显示在侧边栏 */
async function loadCurrentUser() {
    const data = await apiFetch("/api/auth/me");
    if (data && data.username) {
        currentUserEl.textContent = data.username;
        avatarLetterEl.textContent = data.username.charAt(0).toUpperCase();
    }
}

// ==================== 会话管理 ====================

/** 从 /api/sessions 加载已保存的会话列表 */
async function loadSessions() {
    sessionsCache = await apiFetch("/api/sessions") || [];
    sessionsLoaded = true;
    renderSessionList();
}

/** 渲染侧边栏的已保存会话列表：每个会话显示名称、连接信息和操作按钮（连接/编辑/删除） */
function renderSessionList() {
    if (!sessionsCache.length) {
        sessionListEl.innerHTML = `<div class="session-empty">
            <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" opacity="0.3">
                <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                <line x1="8" y1="21" x2="16" y2="21"></line>
                <line x1="12" y1="17" x2="12" y2="21"></line>
            </svg>
            <span>${escapeHtml(t("empty.savedSessions"))}</span>
        </div>`;
        return;
    }

    const html = sessionsCache.map((s) => {
        const safeName = escapeHtml(s.name || t("session.unnamed"));
        const safeMeta = escapeHtml(`${s.username}@${s.host}:${s.port}`);
        return `
            <div class="session-item" data-id="${s.id}">
                <div class="session-info">
                    <div class="session-name-row">
                        <svg class="session-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                            <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                            <path d="M7 8l3 3-3 3"></path>
                            <path d="M12 14h5"></path>
                        </svg>
                        <span class="session-name">${safeName}</span>
                    </div>
                    <div class="session-meta">${safeMeta}</div>
                </div>
                <div class="session-actions">
                    <button class="action-btn load-btn" data-load-id="${s.id}" title="${escapeHtml(t("session.action.loadTitle"))}">
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                            <path d="M5 3l14 9-14 9V3z"></path>
                        </svg>
                    </button>
                    <button class="action-btn secondary edit-btn" data-edit-id="${s.id}" title="${escapeHtml(t("session.action.editTitle"))}">
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                        </svg>
                    </button>
                    <button class="action-btn secondary delete-btn" data-delete-id="${s.id}" title="${escapeHtml(t("session.action.deleteTitle"))}">
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        </svg>
                    </button>
                </div>
            </div>
        `;
    }).join("");

    sessionListEl.innerHTML = html;

    sessionListEl.querySelectorAll(".load-btn").forEach((btn) => {
        btn.addEventListener("click", async (e) => {
            e.stopPropagation();
            const id = btn.dataset.loadId;
            try {
                // 每次从会话列表点击“连接”都新建标签，避免复用当前标签状态。
                createAndSwitchTab();
                await loadSessionIntoActiveTab(id);
                toggleDrawer(false); // Close drawer after loading
                closeSidebarOnMobile(); // Close sidebar on mobile
                connectActiveTab(); // Auto connect
            } catch (e) {
                setTabStatus(activeTab(), e.message, "error");
            }
        });
    });
    sessionListEl.querySelectorAll(".edit-btn").forEach((btn) => {
        btn.addEventListener("click", async (e) => {
            e.stopPropagation();
            const id = btn.dataset.editId;
            await loadSessionIntoActiveTab(id);
            toggleDrawer(true); // Open drawer for editing
        });
    });
    sessionListEl.querySelectorAll(".delete-btn").forEach((btn) => {
        btn.addEventListener("click", async (e) => {
            e.stopPropagation();
            const id = btn.dataset.deleteId;
            if (confirm(t("confirm.deleteSession"))) {
                await deleteSession(id);
            }
        });
    });
}

/** 从后端加载指定会话的详细信息（含解密凭据），填充到当前活跃标签的表单 */
async function loadSessionIntoActiveTab(id) {
    const tab = activeTab();
    if (!tab) {
        return;
    }
    if (tab.connected) {
        setTabStatus(tab, "请先断开当前标签连接，再加载其他会话。", "error");
        return;
    }
    const detail = await apiFetch(`/api/sessions/${id}`);
    if (!detail) {
        return;
    }
    tab.profile.sessionId = detail.id;
    tab.profile.sessionName = detail.name || "";
    tab.profile.host = detail.host || "";
    tab.profile.port = detail.port || 22;
    tab.profile.username = detail.username || "";
    tab.profile.authType = detail.authType || "PASSWORD";
    tab.profile.hostFingerprint = detail.hostFingerprint || "";
    tab.profile.password = detail.password || "";
    tab.profile.privateKey = detail.privateKey || "";
    tab.profile.passphrase = detail.passphrase || "";
    tab.profile.saveCredentials = !!detail.hasSavedCredentials;
    tab.profile.hasSavedCredentials = !!detail.hasSavedCredentials;
    tab.profile.sftpPath = tab.profile.sftpPath || ".";

    loadFormFromTab(tab);
    if (detail.hasSavedCredentials) {
        setTabStatus(tab, "会话已加载，已自动回填解密凭据。");
    } else {
        setTabStatus(tab, "会话已加载，请输入凭据后连接。");
    }
    renderTabs();
}

/** 将当前标签的会话配置保存到后端（POST /api/sessions），支持加密凭据存储 */
async function saveCurrentSession() {
    const tab = activeTab();
    if (!tab) {
        return;
    }
    const p = snapshotProfileFromForm(tab);

    if (!p.sessionName || !p.host || !p.username) {
        setTabStatus(tab, "保存会话前请至少填写 会话名/主机/用户名", "error");
        return;
    }
    if (!p.hostFingerprint) {
        setTabStatus(tab, "请先连接并确认主机指纹后再保存会话", "error");
        return;
    }

    const saved = await apiFetch("/api/sessions", {
        method: "POST",
        body: JSON.stringify({
            id: p.sessionId,
            name: p.sessionName,
            host: p.host,
            port: p.port,
            username: p.username,
            authType: p.authType,
            hostFingerprint: normalizeFingerprint(p.hostFingerprint),
            saveCredentials: !!p.saveCredentials,
            password: p.saveCredentials ? p.password : null,
            privateKey: p.saveCredentials ? p.privateKey : null,
            passphrase: p.saveCredentials ? p.passphrase : null
        })
    });

    tab.profile.sessionId = saved.id;
    tab.profile.saveCredentials = !!saved.hasSavedCredentials;
    tab.profile.hasSavedCredentials = !!saved.hasSavedCredentials;
    if (!saved.hasSavedCredentials) {
        tab.profile.password = "";
        tab.profile.privateKey = "";
        tab.profile.passphrase = "";
    }
    tab.profile.hostFingerprint = saved.hostFingerprint || tab.profile.hostFingerprint;
    loadFormFromTab(tab);
    setTabStatus(tab, "会话保存成功", "ok");
    await loadSessions();
    renderTabs();
}

async function deleteSession(id) {
    await apiFetch(`/api/sessions/${id}`, { method: "DELETE" });
    await loadSessions();
}

/** HTML 实体转义，防止 XSS 攻击 */
function escapeHtml(str) {
    return String(str)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#039;");
}

/** 创建新标签并立即切换过去 */
function createAndSwitchTab(profile = {}) {
    try {
        const tab = makeTab(profile);
        renderTabs();
        switchTab(tab.id);
    } catch (e) {
        setTabStatus(activeTab(), e.message, "error");
        connectBtn.disabled = true;
        disconnectBtn.disabled = true;
        saveSessionBtn.disabled = true;
    }
}

/** 切换配置抽屉面板的显示/隐藏 */
function toggleDrawer(show) {
    if (show) {
        configDrawer.classList.add("open");
    } else {
        configDrawer.classList.remove("open");
    }
}

/** 语言切换后重新渲染所有 UI 组件 */
function rerenderForLanguageChange() {
    applyTranslations(document);
    syncFullscreenButton();
    renderTabs();
    if (sessionsLoaded) {
        renderSessionList();
    }
    const tab = activeTab();
    renderSftpState(tab);
    renderSftpEntries(tab);
    renderForwardList(tab);
    if (tab) {
        renderStatus(tab);
    }
}

// ==================== 应用启动 ====================

/**
 * 应用初始化入口函数。
 * 
 * 初始化流程：
 * 1. 应用国际化翻译
 * 2. 绑定表单事件监听（input/change 同步到活跃标签）
 * 3. 绑定 SFTP 面板事件（刷新、上级目录、创建目录、上传文件/文件夹）
 * 4. 绑定全屏按钮和端口转发按钮
 * 5. 绑定侧边栏折叠/展开按钮
 * 6. 应用已保存的终端主题
 * 7. 创建初始标签
 * 8. 加载当前用户信息和已保存会话列表
 */
async function bootstrap() {
    applyTranslations(document);
    mountLanguageSelector(languageSelectEl);
    window.addEventListener("webssh:languagechange", rerenderForLanguageChange);

    Object.values(formEls).filter(Boolean).forEach((el) => {
        el.addEventListener("input", syncFormToActiveTab);
        el.addEventListener("change", syncFormToActiveTab);
    });

    // Handle Auth Type visibility
    formEls.authType.addEventListener("change", updateAuthFieldsVisibility);

    if (sftpEls.path) {
        sftpEls.path.addEventListener("change", () => {
            const tab = activeTab();
            if (!tab) {
                return;
            }
            tab.profile.sftpPath = normalizeRemotePath(sftpEls.path.value);
            sftpEls.path.value = tab.profile.sftpPath;
        });
        sftpEls.path.addEventListener("keydown", (event) => {
            if (event.key !== "Enter") {
                return;
            }
            event.preventDefault();
            requestSftpList(activeTab());
        });
    }
    if (sftpEls.refreshBtn) {
        sftpEls.refreshBtn.addEventListener("click", () => requestSftpList(activeTab()));
    }
    if (sftpEls.upBtn) {
        sftpEls.upBtn.addEventListener("click", () => {
            const tab = activeTab();
            if (!tab) {
                return;
            }
            requestSftpList(tab, parentRemotePath(tab.profile.sftpPath || "."));
        });
    }
    if (sftpEls.mkdirBtn) {
        sftpEls.mkdirBtn.addEventListener("click", () => requestSftpMkdir(activeTab()));
    }
    if (sftpEls.uploadBtn && sftpEls.uploadFileInput) {
        sftpEls.uploadBtn.addEventListener("click", () => sftpEls.uploadFileInput.click());
        sftpEls.uploadFileInput.addEventListener("change", async () => {
            const files = Array.from(sftpEls.uploadFileInput.files || []);
            try {
                await requestSftpUpload(activeTab(), files, false);
            } catch (e) {
                setTabStatus(activeTab(), e.message || "上传失败", "error");
            } finally {
                sftpEls.uploadFileInput.value = "";
            }
        });
    }
    if (sftpEls.uploadFolderBtn && sftpEls.uploadFolderInput) {
        sftpEls.uploadFolderBtn.addEventListener("click", () => sftpEls.uploadFolderInput.click());
        sftpEls.uploadFolderInput.addEventListener("change", async () => {
            const files = Array.from(sftpEls.uploadFolderInput.files || []);
            try {
                await requestSftpUpload(activeTab(), files, true);
            } catch (e) {
                setTabStatus(activeTab(), e.message || "上传目录失败", "error");
            } finally {
                sftpEls.uploadFolderInput.value = "";
            }
        });
    }
    if (sftpEls.toggleBtn) {
        sftpEls.toggleBtn.addEventListener("click", () => setSftpPanelVisible(!sftpPanelOpen));
    }
    if (sftpEls.closeBtn) {
        sftpEls.closeBtn.addEventListener("click", () => setSftpPanelVisible(false));
    }
    if (fullscreenBtn) {
        fullscreenBtn.addEventListener("click", () => {
            toggleFullscreen();
        });
        ["fullscreenchange", "webkitfullscreenchange", "mozfullscreenchange", "MSFullscreenChange"]
            .forEach((eventName) => {
                document.addEventListener(eventName, () => {
                    syncFullscreenButton();
                    setTimeout(refreshTerminalAfterLayoutChange, 60);
                });
            });
        syncFullscreenButton();
    }

    if (forwardEls.addBtn) {
        forwardEls.addBtn.addEventListener("click", () => addForward(activeTab()));
    }
    if (forwardEls.refreshBtn) {
        forwardEls.refreshBtn.addEventListener("click", () => requestForwardList(activeTab()));
    }

    connectBtn.addEventListener("click", connectActiveTab);
    disconnectBtn.addEventListener("click", disconnectActiveTab);
    saveSessionBtn.addEventListener("click", async () => {
        try {
            await saveCurrentSession();
            toggleDrawer(false);
        } catch (e) {
            setTabStatus(activeTab(), e.message, "error");
        }
    });
    newTabBtn.addEventListener("click", () => {
        createAndSwitchTab();
        toggleDrawer(true);
    });

    openAddSessionBtn.addEventListener("click", () => {
        // “添加”应进入全新未连接会话，避免复用当前已连接标签导致按钮显示“断开连接”。
        createAndSwitchTab();
        toggleDrawer(true);
    });

    closeDrawerBtn.addEventListener("click", () => toggleDrawer(false));

    window.addEventListener("resize", () => {
        updateKeyboardOffset();
        const tab = activeTab();
        if (!tab) {
            return;
        }
        tab.fitAddon.fit();
        sendResize(tab);
    });

    const sidebarToggle = document.getElementById("sidebarToggle");
    const sidebar = document.querySelector(".sidebar");
    const backdrop = document.getElementById("sidebarBackdrop");

    if (sidebarToggle && sidebar) {
        sidebarToggle.addEventListener("click", () => {
            const willSetCollapsed = !sidebar.classList.contains("collapsed");
            sidebar.classList.toggle("collapsed");

            if (backdrop) {
                if (willSetCollapsed) {
                    backdrop.classList.remove("active");
                } else if (window.innerWidth <= 900) {
                    backdrop.classList.add("active");
                }
            }

            // 等待 CSS 过渡动画结束后触发 resize 以重对齐终端
            setTimeout(() => {
                window.dispatchEvent(new Event("resize"));
            }, 305);
        });
    }

    if (backdrop) {
        backdrop.addEventListener("click", () => {
            closeSidebarOnMobile();
        });
    }

    // 移动端初始化状态
    if (window.innerWidth <= 900) {
        if (sidebar) sidebar.classList.add("collapsed");
    }
    updateKeyboardOffset();
    if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", updateKeyboardOffset);
        window.visualViewport.addEventListener("scroll", updateKeyboardOffset);
    }

    const savedThemeId = getSavedThemeId();
    applyThemeCssVars(TERMINAL_THEMES[savedThemeId]);

    const themeSelectEl = document.getElementById("themeSelect");
    if (themeSelectEl) {
        themeSelectEl.value = savedThemeId;
        const dot = document.getElementById("themeColorDot");
        if (dot) {
            dot.style.background = TERMINAL_THEMES[savedThemeId].cursor;
        }
        themeSelectEl.addEventListener("change", () => {
            applyThemeToAllTerminals(themeSelectEl.value);
        });
    }

    setSftpPanelVisible(false);
    createAndSwitchTab();
    updateButtons();
    bindMobileKeybar();
    bindTerminalContextMenu();

    try {
        await loadCurrentUser();
        await loadSessions();
    } catch (e) {
        setTabStatus(activeTab(), e.message, "error");
    }

    // 初始化机器人设置面板
    initBotSettings();
}

bootstrap();

// ==================== 机器人设置面板逻辑 ====================

const BOT_UI_CONFIGS = {
    "telegram": {
        type: "telegram",
        saveButtonId: "telegramSaveBtn",
        restartButtonId: "telegramRestartBtn",
        badgeId: "telegramRunningBadge",
        indicatorId: "telegramStatusIndicator",
        statusTextId: "telegramStatusText",
        fill(settings) {
            const config = settings.config || {};
            const tokenEl = document.getElementById("telegramBotToken");
            const usernameEl = document.getElementById("telegramBotUsername");
            const allowedEl = document.getElementById("telegramAllowedUsers");
            const enabledEl = document.getElementById("telegramEnabled");

            if (tokenEl) tokenEl.value = config.token || "";
            if (usernameEl) usernameEl.value = config.botUsername || "";
            const sshUsernameEl = document.getElementById("telegramSshUsername");
            if (sshUsernameEl) sshUsernameEl.value = settings.sshUsername || "admin";
            if (allowedEl) allowedEl.value = (settings.allowedUserIds || []).join("\n");
            if (enabledEl) enabledEl.checked = settings.enabled || false;
        },
        buildPayload() {
            const token = (document.getElementById("telegramBotToken")?.value || "").trim();
            const botUsername = (document.getElementById("telegramBotUsername")?.value || "").trim();
            const enabled = document.getElementById("telegramEnabled")?.checked || false;
            const allowedUserIds = parseTextareaLines(document.getElementById("telegramAllowedUsers")?.value);

            if (enabled && (!token || !botUsername)) {
                throw new Error("启用 Telegram Bot 之前请填写 Token 和 Username");
            }

            const sshUsername = (document.getElementById("telegramSshUsername")?.value || "admin").trim();

            return {
                type: "telegram",
                enabled: enabled,
                sshUsername: sshUsername,
                config: {
                    token: token,
                    botUsername: botUsername
                },
                allowedUserIds: allowedUserIds
            };
        }
    },
    "qq-official": {
        type: "qq-official",
        saveButtonId: "qqOfficialSaveBtn",
        restartButtonId: "qqOfficialRestartBtn",
        badgeId: "qqOfficialRunningBadge",
        indicatorId: "qqOfficialStatusIndicator",
        statusTextId: "qqOfficialStatusText",
        fill(settings) {
            const config = settings.config || {};
            const appIdEl = document.getElementById("qqOfficialAppId");
            const secretEl = document.getElementById("qqOfficialAppSecret");
            const usersEl = document.getElementById("qqOfficialAllowedUsers");
            const enabledEl = document.getElementById("qqOfficialEnabled");

            if (appIdEl) appIdEl.value = config.appId || "";
            if (secretEl) secretEl.value = config.appSecret || "";
            const sshUsernameEl = document.getElementById("qqOfficialSshUsername");
            if (sshUsernameEl) sshUsernameEl.value = settings.sshUsername || "admin";
            if (usersEl) usersEl.value = (settings.allowedUserIds || []).join("\n");
            if (enabledEl) enabledEl.checked = settings.enabled || false;
        },
        buildPayload() {
            const appId = (document.getElementById("qqOfficialAppId")?.value || "").trim();
            const appSecret = (document.getElementById("qqOfficialAppSecret")?.value || "").trim();
            const enabled = document.getElementById("qqOfficialEnabled")?.checked || false;
            const allowedUserIds = parseTextareaLines(document.getElementById("qqOfficialAllowedUsers")?.value);

            if (enabled && (!appId || !appSecret)) {
                throw new Error("启用 QQ 私聊机器人之前请填写 App ID 和 App Secret");
            }

            const sshUsername = (document.getElementById("qqOfficialSshUsername")?.value || "admin").trim();

            return {
                type: "qq-official",
                enabled: enabled,
                sshUsername: sshUsername,
                config: {
                    appId: appId,
                    appSecret: appSecret
                },
                allowedUserIds: allowedUserIds
            };
        }
    },
    "wechat": {
        type: "wechat",
        saveButtonId: "wechatSaveBtn",
        restartButtonId: "wechatRestartBtn",
        badgeId: "wechatRunningBadge",
        indicatorId: "wechatStatusIndicator",
        statusTextId: "wechatStatusText",
        fill(settings) {
            const config = settings.config || {};
            const tokenEl = document.getElementById("wechatChannelToken");
            const jwtEl = document.getElementById("wechatJwtToken");
            const usersEl = document.getElementById("wechatAllowedUsers");
            const enabledEl = document.getElementById("wechatEnabled");

            if (tokenEl) tokenEl.value = config.channelToken || "";
            if (jwtEl) jwtEl.value = config.jwtToken || "";
            const sshUsernameEl = document.getElementById("wechatSshUsername");
            if (sshUsernameEl) sshUsernameEl.value = settings.sshUsername || "admin";
            if (usersEl) usersEl.value = (settings.allowedUserIds || []).join("\n");
            if (enabledEl) enabledEl.checked = settings.enabled || false;
        },
        buildPayload() {
            const channelToken = (document.getElementById("wechatChannelToken")?.value || "").trim();
            const jwtToken = (document.getElementById("wechatJwtToken")?.value || "").trim();
            const enabled = document.getElementById("wechatEnabled")?.checked || false;
            const allowedUserIds = parseTextareaLines(document.getElementById("wechatAllowedUsers")?.value);

            if (enabled && !channelToken) {
                throw new Error("启用微信 ClawBot 之前请填写 Channel Token");
            }

            const sshUsername = (document.getElementById("wechatSshUsername")?.value || "admin").trim();

            return {
                type: "wechat",
                enabled: enabled,
                sshUsername: sshUsername,
                config: {
                    channelToken: channelToken,
                    jwtToken: jwtToken || undefined
                },
                allowedUserIds: allowedUserIds
            };
        }
    },
    "weixin-clawbot": {
        type: "weixin-clawbot",
        saveButtonId: "weixinCbSaveBtn",
        restartButtonId: "weixinCbRestartBtn",
        badgeId: "weixinCbRunningBadge",
        indicatorId: "weixinCbStatusIndicator",
        statusTextId: "weixinCbStatusText",
        fill(settings) {
            const config = settings.config || {};
            const tokenEl = document.getElementById("weixinCbBotToken");
            const baseUrlEl = document.getElementById("weixinCbBaseUrl");
            const sshEl = document.getElementById("weixinCbSshUsername");
            const usersEl = document.getElementById("weixinCbAllowedUsers");
            const enabledEl = document.getElementById("weixinCbEnabled");
            if (tokenEl) tokenEl.value = config.botToken || "";
            if (baseUrlEl) baseUrlEl.value = config.baseUrl || "";
            if (sshEl) sshEl.value = settings.sshUsername || "admin";
            if (usersEl) usersEl.value = (settings.allowedUserIds || []).join("\n");
            if (enabledEl) enabledEl.checked = settings.enabled || false;
        },
        buildPayload() {
            const botToken = (document.getElementById("weixinCbBotToken")?.value || "").trim();
            const baseUrl = (document.getElementById("weixinCbBaseUrl")?.value || "").trim();
            const enabled = document.getElementById("weixinCbEnabled")?.checked || false;
            const sshUsername = (document.getElementById("weixinCbSshUsername")?.value || "admin").trim();
            const allowedUserIds = parseTextareaLines(document.getElementById("weixinCbAllowedUsers")?.value);
            if (enabled && !botToken) {
                throw new Error("启用微信 ClawBot 之前请先扫码获取 Bot Token");
            }
            return {
                type: "weixin-clawbot",
                enabled,
                sshUsername,
                config: { botToken, ...(baseUrl ? { baseUrl } : {}) },
                allowedUserIds
            };
        }
    }
};

function initBotSettings() {
    const openBtn = document.getElementById("openBotSettingsBtn");
    const closeBtn = document.getElementById("closeBotDrawerBtn");
    const drawer = document.getElementById("botSettingsDrawer");

    if (!openBtn || !drawer) return;

    openBtn.addEventListener("click", () => {
        drawer.classList.toggle("open");
        if (drawer.classList.contains("open")) {
            loadAllBotSettings();
        }
    });

    if (closeBtn) {
        closeBtn.addEventListener("click", () => {
            drawer.classList.remove("open");
        });
    }

    Object.values(BOT_UI_CONFIGS).forEach((config) => {
        const saveBtn = document.getElementById(config.saveButtonId);
        const restartBtn = document.getElementById(config.restartButtonId);
        if (saveBtn) {
            saveBtn.addEventListener("click", () => saveBotSettings(config));
        }
        if (restartBtn) {
            restartBtn.addEventListener("click", () => restartBot(config));
        }
    });

    // 微信扫码登录按钮
    const qrLoginBtn = document.getElementById("wechatQrLoginBtn");
    if (qrLoginBtn) {
        qrLoginBtn.addEventListener("click", startWechatQrLogin);
    }

    // 微信 ClawBot (iLink) 扫码按钮
    const cbQrBtn = document.getElementById("weixinCbQrLoginBtn");
    if (cbQrBtn) {
        cbQrBtn.addEventListener("click", startWeixinCbQrLogin);
    }

    // 初始加载状态
    loadAllBotStatuses();
}

function parseTextareaLines(value) {
    const text = (value || "").trim();
    return text ? text.split("\n").map(s => s.trim()).filter(Boolean) : [];
}

async function loadAllBotSettings() {
    try {
        await Promise.all(Object.values(BOT_UI_CONFIGS).map(loadBotSettings));
    } catch (e) {
        console.error("加载机器人设置失败:", e);
    }
    loadAllBotStatuses();
}

async function loadBotSettings(config) {
    const resp = await fetch(`/api/bot-settings/${config.type}`);
    if (!resp.ok) return;
    const settings = await resp.json();
    config.fill(settings);
    updateBotEnabledUI(config, settings.enabled || false);
}

async function saveBotSettings(config) {
    const saveBtn = document.getElementById(config.saveButtonId);
    let body;
    try {
        body = config.buildPayload();
    } catch (e) {
        alert(e.message);
        return;
    }

    try {
        if (saveBtn) {
            saveBtn.disabled = true;
            saveBtn.querySelector("span").textContent = "保存中...";
        }

        const resp = await fetch(`/api/bot-settings/${config.type}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
        });
        if (!resp.ok) throw new Error("保存失败: " + resp.status);
        await new Promise(r => setTimeout(r, 1500));
        await loadBotSettings(config);
        await loadAllBotStatuses();
    } catch (e) {
        alert("保存失败: " + e.message);
    } finally {
        if (saveBtn) {
            saveBtn.disabled = false;
            saveBtn.querySelector("span").textContent = "保存并应用";
        }
    }
}

async function restartBot(config) {
    const restartBtn = document.getElementById(config.restartButtonId);
    try {
        if (restartBtn) {
            restartBtn.disabled = true;
            restartBtn.querySelector("span").textContent = "重启中...";
        }

        const resp = await fetch(`/api/bot-settings/${config.type}/restart`, { method: "POST" });
        const status = await resp.json();
        if (status.success === false) {
            throw new Error(status.message || "重启失败");
        }
        await new Promise(r => setTimeout(r, 1500));
        await loadAllBotStatuses();
    } catch (e) {
        alert("重启失败: " + e.message);
    } finally {
        if (restartBtn) {
            restartBtn.disabled = false;
            restartBtn.querySelector("span").textContent = "重启";
        }
    }
}

async function loadAllBotStatuses() {
    const results = await Promise.all(Object.values(BOT_UI_CONFIGS).map(loadBotStatus));
    updateBotTriggerDot(results.some(Boolean));
}

async function loadBotStatus(config) {
    try {
        const resp = await fetch(`/api/bot-settings/${config.type}/status`);
        if (!resp.ok) return false;
        const status = await resp.json();
        updateBotRunningUI(config, status.running, status.statusMessage);
        return !!status.running;
    } catch (e) {
        updateBotRunningUI(config, false, "状态获取失败");
        return false;
    }
}

function updateBotEnabledUI(config, enabled) {
    const badge = document.getElementById(config.badgeId);
    if (badge) {
        badge.style.display = enabled ? "" : "none";
        if (!enabled) {
            badge.textContent = "已停用";
        }
    }
}

function updateBotRunningUI(config, running, message) {
    const indicator = document.getElementById(config.indicatorId);
    const statusText = document.getElementById(config.statusTextId);
    const badge = document.getElementById(config.badgeId);

    if (indicator) {
        indicator.className = "bot-indicator " + (running ? "bot-indicator-running" : "bot-indicator-stopped");
    }
    if (statusText) {
        statusText.textContent = message || (running ? "运行中" : "已停止");
    }
    if (badge) {
        badge.style.display = running ? "" : "none";
        badge.textContent = running ? "运行中" : "已停止";
    }
}

function updateBotTriggerDot(anyRunning) {
    const dot = document.getElementById("botStatusDot");
    if (dot) {
        dot.classList.toggle("running", anyRunning);
    }
}

// ==================== 微信扫码登录 ====================

async function startWechatQrLogin() {
    const modal = document.getElementById("wechatQrModal");
    const canvas = document.getElementById("qrCanvas");
    const loading = document.getElementById("qrLoading");
    const errorDiv = document.getElementById("qrError");
    const step2 = document.getElementById("qrStep2");
    const scanHint = document.getElementById("qrScanHint");
    const fallbackLink = document.getElementById("qrFallbackLink");
    const callbackInput = document.getElementById("qrCallbackUrl");
    const statusDiv = document.getElementById("qrExchangeStatus");

    // 重置状态
    modal.style.display = "flex";
    canvas.style.display = "none";
    loading.style.display = "flex";
    errorDiv.style.display = "none";
    step2.style.display = "none";
    scanHint.style.display = "none";
    fallbackLink.style.display = "none";
    if (callbackInput) callbackInput.value = "";
    if (statusDiv) { statusDiv.style.display = "none"; statusDiv.textContent = ""; }

    try {
        const resp = await fetch("/api/wechat-login/qr-url", { method: "POST" });
        const data = await resp.json();
        if (!data.success) throw new Error(data.message || "获取二维码失败");

        modal.dataset.guid = data.guid;
        modal.dataset.state = data.state;

        loading.style.display = "none";

        // QR 库加载失败容错
        if (typeof QRCode === "undefined") {
            fallbackLink.href = data.qrUrl;
            fallbackLink.style.display = "inline";
            fallbackLink.textContent = "QR库未加载，点击在浏览器中打开扫码页面";
        } else {
            canvas.style.display = "block";
            canvas.innerHTML = "";
            new QRCode(canvas, {
                text: data.qrUrl,
                width: 256, height: 256,
                colorDark: "#000000", colorLight: "#ffffff",
                correctLevel: QRCode.CorrectLevel.L
            });
            fallbackLink.href = data.qrUrl;
            fallbackLink.style.display = "inline";
            fallbackLink.textContent = "在浏览器中打开";
        }

        scanHint.style.display = "block";
        step2.style.display = "block";
    } catch (e) {
        loading.style.display = "none";
        errorDiv.style.display = "block";
        errorDiv.textContent = "获取二维码失败: " + e.message;
    }
}

// 关闭 modal
document.getElementById("qrModalCloseBtn")?.addEventListener("click", () => {
    document.getElementById("wechatQrModal").style.display = "none";
});
document.getElementById("wechatQrModal")?.addEventListener("click", (e) => {
    if (e.target === e.currentTarget) e.currentTarget.style.display = "none";
});

// 获取 Token
document.getElementById("qrExchangeBtn")?.addEventListener("click", async () => {
    const modal = document.getElementById("wechatQrModal");
    const urlInput = document.getElementById("qrCallbackUrl");
    const btn = document.getElementById("qrExchangeBtn");
    const statusDiv = document.getElementById("qrExchangeStatus");
    const rawUrl = (urlInput?.value || "").trim();

    if (!rawUrl) { alert("请粘贴微信扫码后跳转的完整 URL"); return; }

    let code;
    try {
        const url = new URL(rawUrl);
        code = url.searchParams.get("code");
    } catch {
        alert("URL 格式不正确，请粘贴完整的跳转地址");
        return;
    }
    if (!code) { alert("URL 中未包含 code 参数，请确认已完成微信扫码授权"); return; }

    const guid = modal.dataset.guid;
    const state = modal.dataset.state;

    try {
        btn.disabled = true;
        btn.querySelector("span").textContent = "获取中...";
        statusDiv.style.display = "block";
        statusDiv.textContent = "正在换取 Token...";
        statusDiv.style.color = "var(--text-secondary)";

        const resp = await fetch("/api/wechat-login/exchange", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ guid, code, state })
        });
        const data = await resp.json();
        if (!data.success) throw new Error(data.message || "换取 Token 失败");

        // 自动填入表单
        const tokenEl = document.getElementById("wechatChannelToken");
        const jwtEl = document.getElementById("wechatJwtToken");
        if (tokenEl && data.channelToken) tokenEl.value = data.channelToken;
        if (jwtEl && data.jwtToken) jwtEl.value = data.jwtToken;

        statusDiv.textContent = "Token 获取成功，已自动填入表单";
        statusDiv.style.color = "var(--accent-green, #4ade80)";

        setTimeout(() => { modal.style.display = "none"; }, 1500);
    } catch (e) {
        statusDiv.textContent = "失败: " + e.message;
        statusDiv.style.color = "var(--accent-red, #f87171)";
    } finally {
        btn.disabled = false;
        btn.querySelector("span").textContent = "获取 Token";
    }
});

// ==================== 微信 ClawBot (iLink) 扫码登录 ====================

let weixinCbPollTimer = null;

async function startWeixinCbQrLogin() {
    const modal = document.getElementById("wechatQrModal");
    const canvas = document.getElementById("qrCanvas");
    const loading = document.getElementById("qrLoading");
    const errorDiv = document.getElementById("qrError");
    const step2 = document.getElementById("qrStep2");
    const scanHint = document.getElementById("qrScanHint");
    const fallbackLink = document.getElementById("qrFallbackLink");
    const callbackInput = document.getElementById("qrCallbackUrl");
    const statusDiv = document.getElementById("qrExchangeStatus");
    const exchangeBtn = document.getElementById("qrExchangeBtn");

    if (weixinCbPollTimer) { clearInterval(weixinCbPollTimer); weixinCbPollTimer = null; }

    modal.style.display = "flex";
    canvas.style.display = "none";
    loading.style.display = "flex";
    loading.querySelector("span").textContent = "正在获取登录二维码...";
    errorDiv.style.display = "none";
    step2.style.display = "none";
    scanHint.style.display = "none";
    fallbackLink.style.display = "none";
    if (exchangeBtn) exchangeBtn.style.display = "none";
    if (callbackInput) callbackInput.parentElement.style.display = "none";
    if (statusDiv) { statusDiv.style.display = "block"; statusDiv.textContent = ""; }

    const baseUrl = (document.getElementById("weixinCbBaseUrl")?.value || "").trim();

    try {
        const resp = await fetch("/api/weixin-clawbot-login/qr-start", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(baseUrl ? { baseUrl } : {})
        });
        const data = await resp.json();
        if (!data.success) throw new Error(data.message || "获取二维码失败");

        loading.style.display = "none";

        if (typeof QRCode !== "undefined") {
            canvas.style.display = "block";
            canvas.innerHTML = "";
            new QRCode(canvas, {
                text: data.qrcodeUrl,
                width: 256, height: 256,
                colorDark: "#000000", colorLight: "#ffffff",
                correctLevel: QRCode.CorrectLevel.L
            });
        } else {
            fallbackLink.href = data.qrcodeUrl;
            fallbackLink.style.display = "inline";
            fallbackLink.textContent = "QR库未加载，点击打开";
        }

        scanHint.style.display = "block";
        scanHint.textContent = "请使用微信扫描上方二维码";
        step2.style.display = "block";
        statusDiv.style.display = "block";
        statusDiv.textContent = "等待扫码...";
        statusDiv.style.color = "var(--text-secondary)";

        const sessionId = data.sessionId;
        weixinCbPollTimer = setInterval(async () => {
            try {
                const pr = await fetch("/api/weixin-clawbot-login/qr-poll", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ sessionId })
                });
                const pd = await pr.json();
                if (!pd.success && pd.status === "expired") {
                    clearInterval(weixinCbPollTimer); weixinCbPollTimer = null;
                    statusDiv.textContent = "二维码已过期，请关闭后重试";
                    statusDiv.style.color = "var(--accent-red, #f87171)";
                    return;
                }
                if (pd.status === "scaned") {
                    statusDiv.textContent = "已扫码，请在微信中确认...";
                    statusDiv.style.color = "var(--theme-accent)";
                } else if (pd.status === "confirmed") {
                    clearInterval(weixinCbPollTimer); weixinCbPollTimer = null;
                    const tokenEl = document.getElementById("weixinCbBotToken");
                    const baseUrlEl = document.getElementById("weixinCbBaseUrl");
                    if (tokenEl && pd.botToken) tokenEl.value = pd.botToken;
                    if (baseUrlEl && pd.baseUrl) baseUrlEl.value = pd.baseUrl;
                    statusDiv.textContent = "Token 获取成功，已自动填入！";
                    statusDiv.style.color = "var(--accent-green, #4ade80)";
                    setTimeout(() => { modal.style.display = "none"; }, 1500);
                }
            } catch { /* 轮询错误静默 */ }
        }, 2500);
    } catch (e) {
        loading.style.display = "none";
        errorDiv.style.display = "block";
        errorDiv.textContent = "获取二维码失败: " + e.message;
    }
}

// 关闭 modal 时清理 iLink 轮询
document.getElementById("qrModalCloseBtn")?.addEventListener("click", () => {
    if (weixinCbPollTimer) { clearInterval(weixinCbPollTimer); weixinCbPollTimer = null; }
});
