/**
 * Hera Client Application Script
 */

// ─── Application State ────────────────────────────────────────────────────────
let recommendations = [];
let libraryData = [];
let ws = null;
let batchState = {
    active: false,
    total: 0,
    completed: 0,
    failed: 0,
    skipped: 0,
    currentTrack: '',
    currentProgress: 0,
    currentStatus: 'idle'
};

// ─── Theme Management ─────────────────────────────────────────────────────────
function initTheme() {
    const saved = localStorage.getItem('hera-theme') || 'dark';
    applyTheme(saved);
}

function applyTheme(theme) {
    const btn = document.getElementById('theme-toggle-btn');
    if (theme === 'light') {
        document.documentElement.setAttribute('data-theme', 'light');
        if (btn) btn.innerText = '[dark mode]';
        localStorage.setItem('hera-theme', 'light');
    } else {
        document.documentElement.removeAttribute('data-theme');
        if (btn) btn.innerText = '[light mode]';
        localStorage.setItem('hera-theme', 'dark');
    }
}

function toggleTheme() {
    const isLight = document.documentElement.getAttribute('data-theme') === 'light';
    applyTheme(isLight ? 'dark' : 'light');
}

// ─── UI Progress & Dashboard Updates ──────────────────────────────────────────
function updateTrackUI(name, percent, statusText, subtext, statusType = 'active') {
    const nameElem = document.getElementById('track-name-display');
    const fillElem = document.getElementById('track-progress-fill');
    const pctElem = document.getElementById('track-progress-pct');
    const pillElem = document.getElementById('track-status-pill');
    const subElem = document.getElementById('track-subtext');
    const cardElem = document.getElementById('card-track');

    const clamped = Math.max(0, Math.min(100, Math.round(percent || 0)));
    if (nameElem && name) { nameElem.innerText = name; nameElem.title = name; }
    if (fillElem) fillElem.style.width = `${clamped}%`;
    if (pctElem) pctElem.innerText = `${clamped}%`;
    if (pillElem && statusText) {
        pillElem.className = `status-pill ${statusType}`;
        pillElem.innerText = statusText;
    }
    if (subElem && subtext) subElem.innerText = subtext;
    if (cardElem) {
        if (statusType === 'active') cardElem.classList.add('active');
        else cardElem.classList.remove('active');
    }
}

function updateBatchUI(completed, total, smoothPercent, subtext, statusType = 'active') {
    const countElem = document.getElementById('batch-count-display');
    const fillElem = document.getElementById('batch-progress-fill');
    const pctElem = document.getElementById('batch-progress-pct');
    const pillElem = document.getElementById('batch-status-pill');
    const subElem = document.getElementById('batch-subtext');
    const cardElem = document.getElementById('card-batch');

    const clamped = Math.max(0, Math.min(100, Math.round(smoothPercent || 0)));
    if (countElem) countElem.innerText = `${completed} / ${total} tracks`;
    if (fillElem) fillElem.style.width = `${clamped}%`;
    if (pctElem) pctElem.innerText = `${clamped}%`;
    if (pillElem) {
        pillElem.className = `status-pill ${statusType}`;
        pillElem.innerText = statusType === 'active' ? '[● running]' : (statusType === 'connected' ? '[✓ complete]' : '[idle]');
    }
    if (subElem && subtext) subElem.innerText = subtext;
    if (cardElem) {
        if (statusType === 'active') cardElem.classList.add('active');
        else cardElem.classList.remove('active');
    }
}

function startBatch(total) {
    if (batchState.active && batchState.total === total && batchState.completed === 0) {
        return;
    }
    batchState.active = true;
    batchState.total = Math.max(1, total || 1);
    batchState.completed = 0;
    batchState.failed = 0;
    batchState.skipped = 0;
    batchState.currentTrack = '';
    batchState.currentProgress = 0;
    batchState.currentStatus = 'downloading';
    setDownloadStatus(true, `[● batch of ${batchState.total}]`);
    updateTrackUI('preparing download...', 0, '[queued]', 'locating stream on youtube music...', 'active');
    updateBatchUI(0, batchState.total, 0, `in-progress: 0 | remaining: ${batchState.total}`, 'active');
    logProgress(`started batch download of ${batchState.total} tracks...`);
}

function setDownloadStatus(active, text) {
    const elem = document.getElementById('download-status');
    if (!elem) return;
    elem.className = active ? 'status-pill active' : (text && text.includes('✓') ? 'status-pill connected' : 'status-pill idle');
    elem.innerText = text || (active ? '[● downloading...]' : '[idle]');
}

function logProgress(msg) {
    const feed = document.getElementById('progress-feed');
    if (!feed) return;
    const ts = new Date().toTimeString().split(' ')[0];
    const line = `[${ts}] ${msg}`;
    if (feed.innerText === 'no active downloads.') {
        feed.innerText = line;
    } else {
        const lines = feed.innerText.split('\n');
        if (lines.length > 50) lines.pop();
        feed.innerText = line + '\n' + lines.join('\n');
    }
}

function clearProgress() {
    const feed = document.getElementById('progress-feed');
    if (feed) feed.innerText = 'no active downloads.';
    if (!batchState.active) {
        updateTrackUI('no active download', 0, '[idle]', 'waiting for download job...', 'idle');
        updateBatchUI(0, 0, 0, 'no active batch', 'idle');
        setDownloadStatus(false, '[idle]');
    }
}

// ─── WebSocket Client ─────────────────────────────────────────────────────────
function connectWS() {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsElem = document.getElementById('ws-text');
    ws = new WebSocket(`${proto}//${window.location.host}/ws/progress`);
    ws.onopen = () => {
        if (wsElem) {
            wsElem.className = 'status-pill connected';
            wsElem.innerText = '[connected]';
        }
    };
    ws.onclose = () => {
        if (wsElem) {
            wsElem.className = 'status-pill disconnected';
            wsElem.innerText = '[disconnected]';
        }
        setTimeout(connectWS, 3000);
    };
    ws.onerror = () => {
        if (wsElem) {
            wsElem.className = 'status-pill disconnected';
            wsElem.innerText = '[error]';
        }
    };
    ws.onmessage = (e) => {
        try {
            handleWSMessage(JSON.parse(e.data));
        } catch (err) {
            logProgress("ws: " + e.data);
        }
    };
}

function handleWSMessage(data) {
    if (data.type === 'batch_start') {
        if (!batchState.active || batchState.total !== data.total) {
            startBatch(data.total);
        }
        return;
    }

    if (data.type === 'batch_progress') {
        if (!batchState.active && data.total) {
            batchState.active = true;
            batchState.total = data.total;
        }
        if (data.completed !== undefined) {
            batchState.completed = data.completed;
            const smoothPct = batchState.total > 0 ? (batchState.completed / batchState.total) * 100 : 0;
            updateBatchUI(batchState.completed, batchState.total, smoothPct, `completed: ${batchState.completed} | remaining: ${Math.max(0, batchState.total - batchState.completed)}`, 'active');
        }
        return;
    }

    if (data.type === 'batch_complete') {
        batchState.active = false;
        const downloadedCount = data.downloaded !== undefined ? data.downloaded : Math.max(0, batchState.completed - batchState.failed - batchState.skipped);
        const failedCount = data.failed !== undefined ? Math.max(data.failed, batchState.failed) : batchState.failed;
        const skippedCount = data.skipped !== undefined ? Math.max(data.skipped, batchState.skipped) : batchState.skipped;
        const totalDone = data.total || (downloadedCount + failedCount + skippedCount) || batchState.total || 0;
        batchState.total = totalDone;
        batchState.completed = totalDone;
        setDownloadStatus(false, '[✓ complete]');
        updateTrackUI(batchState.currentTrack || 'all tracks processed', 100, '[✓ done]', 'all jobs completed', 'connected');
        updateBatchUI(totalDone, totalDone, 100, `finished: ${downloadedCount} downloaded, ${failedCount} failed${skippedCount ? `, ${skippedCount} skipped` : ''}`, 'connected');
        logProgress(`batch complete: ${downloadedCount} downloaded, ${failedCount} failed${skippedCount ? `, ${skippedCount} skipped` : ''}`);
        refreshLibrary();
        setTimeout(() => {
            setDownloadStatus(false, '[idle]');
            const pillT = document.getElementById('track-status-pill');
            const pillB = document.getElementById('batch-status-pill');
            if (pillT) { pillT.className = 'status-pill idle'; pillT.innerText = '[idle]'; }
            if (pillB) { pillB.className = 'status-pill idle'; pillB.innerText = '[idle]'; }
            const cardT = document.getElementById('card-track');
            const cardB = document.getElementById('card-batch');
            if (cardT) cardT.classList.remove('active');
            if (cardB) cardB.classList.remove('active');
        }, 5000);
        const dlBtn = document.getElementById('download-selected-btn');
        if (dlBtn) { dlBtn.disabled = false; dlBtn.innerText = '[download selected]'; }
        return;
    }

    // Song-level event
    const song = data.song || {};
    const songName = (song.artist && song.title) ? `${song.artist} - ${song.title}` : song.name || data.filename || data.message || 'track';
    const status = data.status || 'unknown';
    const progress = data.progress !== undefined ? data.progress : 0;

    if (status === 'downloading' || status === 'queued') {
        if (!batchState.active) {
            batchState.active = true;
            batchState.total = Math.max(1, batchState.total || 1);
        }
        batchState.currentTrack = songName;
        batchState.currentProgress = progress;
        setDownloadStatus(true, `[● ${Math.round(progress)}%]`);
        updateTrackUI(songName, progress, `[● ${Math.round(progress)}%]`, 'downloading audio stream...', 'active');

        const smoothBatchPct = batchState.total > 0
            ? Math.min(100, ((batchState.completed + progress / 100) / batchState.total) * 100)
            : progress;
        const remaining = Math.max(0, batchState.total - batchState.completed - 1);
        updateBatchUI(batchState.completed, batchState.total, smoothBatchPct, `in-progress: 1 | remaining: ${remaining}`, 'active');
    } else if (status === 'finished' || status === 'done') {
        if (!batchState.active) batchState.total = Math.max(1, batchState.total || 1);
        batchState.completed = Math.min(batchState.total, batchState.completed + 1);
        updateTrackUI(songName, 100, '[✓ done]', 'downloaded and saved to library', 'connected');
        const batchPct = batchState.total > 0 ? (batchState.completed / batchState.total) * 100 : 100;
        updateBatchUI(batchState.completed, batchState.total, batchPct, `completed: ${batchState.completed} | remaining: ${Math.max(0, batchState.total - batchState.completed)}`, 'active');
        logProgress(`[✓ done] ${songName}`);
    } else if (status === 'skipped') {
        if (!batchState.active) batchState.total = Math.max(1, batchState.total || 1);
        batchState.skipped++;
        batchState.completed = Math.min(batchState.total, batchState.completed + 1);
        updateTrackUI(songName, 100, '[skipped]', data.message || 'already in library', 'idle');
        const batchPct = batchState.total > 0 ? (batchState.completed / batchState.total) * 100 : 100;
        updateBatchUI(batchState.completed, batchState.total, batchPct, `skipped: ${songName} (already downloaded)`, 'active');
        logProgress(`[skipped] ${songName} (${data.message || 'already downloaded'})`);
    } else if (status === 'error') {
        if (!batchState.active) batchState.total = Math.max(1, batchState.total || 1);
        batchState.failed++;
        batchState.completed = Math.min(batchState.total, batchState.completed + 1);
        updateTrackUI(songName, 0, '[error]', data.message || 'download error', 'disconnected');
        const batchPct = batchState.total > 0 ? (batchState.completed / batchState.total) * 100 : 0;
        updateBatchUI(batchState.completed, batchState.total, batchPct, `failed: ${songName}`, 'active');
        logProgress(`[error] ${songName}: ${data.message || 'failed'}`);
    }
}

// ─── Settings & Cron ──────────────────────────────────────────────────────────
function populateTimeDropdowns() {
    const h = document.getElementById('set-cron-hour');
    const m = document.getElementById('set-cron-minute');
    if (!h || !m) return;
    h.innerHTML = '';
    m.innerHTML = '';
    for (let i = 0; i < 24; i++) h.add(new Option(i.toString().padStart(2, '0'), i));
    [0, 15, 30, 45].forEach(v => m.add(new Option(v.toString().padStart(2, '0'), v)));
}

function parseCron(cron) {
    if (!cron) return { hour: 2, minute: 0 };
    const p = cron.trim().split(/\s+/);
    return p.length >= 3 ? { minute: parseInt(p[1]) || 0, hour: parseInt(p[2]) || 0 } : { minute: 0, hour: 2 };
}

function buildCron() {
    const minute = document.getElementById('set-cron-minute')?.value || '0';
    const hour = document.getElementById('set-cron-hour')?.value || '2';
    return `0 ${minute} ${hour} * * *`;
}

async function openSettings() {
    try {
        const res = await fetch('/api/settings');
        if (!res.ok) throw new Error('failed to load settings');
        const s = await res.json();
        document.getElementById('set-lastfm-key').value = s.lastfmApiKey || '';
        document.getElementById('set-lastfm-user').value = s.lastfmUsername || '';
        document.getElementById('set-max-downloads').value = s.maxDailyDownloads || 5;
        document.getElementById('set-strategy').value = s.scheduledStrategy || 'HYBRID';
        const t = parseCron(s.cronSchedule || '0 0 2 * * *');
        document.getElementById('set-cron-hour').value = t.hour;
        document.getElementById('set-cron-minute').value = t.minute;
        document.getElementById('set-format').value = s.format || 'mp3';
        document.getElementById('set-bitrate').value = s.bitrate || '320';
        document.getElementById('set-organize').checked = !!s.organizeByArtist;
        document.getElementById('set-lyrics').checked = !!s.downloadLyrics;
        document.getElementById('set-cover-art').checked = s.downloadCoverArt !== false;
        document.getElementById('set-cover-resolution').value = s.coverResolution || 600;
    } catch (e) {
        console.error('error loading settings:', e);
    }
}

async function saveSettings() {
    const s = {
        lastfmApiKey: document.getElementById('set-lastfm-key').value.trim(),
        lastfmUsername: document.getElementById('set-lastfm-user').value.trim(),
        maxDailyDownloads: parseInt(document.getElementById('set-max-downloads').value) || 5,
        scheduledStrategy: document.getElementById('set-strategy').value,
        cronSchedule: buildCron(),
        format: document.getElementById('set-format').value,
        bitrate: document.getElementById('set-bitrate').value,
        organizeByArtist: document.getElementById('set-organize').checked,
        downloadLyrics: document.getElementById('set-lyrics').checked,
        downloadCoverArt: document.getElementById('set-cover-art').checked,
        coverResolution: parseInt(document.getElementById('set-cover-resolution').value) || 600
    };
    try {
        const res = await fetch('/api/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(s)
        });
        if (!res.ok) throw new Error('failed to save settings');
        const el = document.getElementById('status-text');
        if (el) {
            el.innerText = 'settings saved and applied.';
            setTimeout(() => { el.innerText = ''; }, 3500);
        }
    } catch (e) {
        alert(`error saving settings: ${e.message}`);
    }
}

async function triggerCleanup() {
    try {
        logProgress('system: starting .webm to .mp3 format cleanup...');
        const res = await fetch('/api/cleanup', { method: 'POST' });
        if (!res.ok) throw new Error(`http ${res.status}`);
        const converted = await res.json();
        logProgress(`system: cleanup finished. converted ${converted.length} files`);
        refreshLibrary();
    } catch (e) {
        alert(`cleanup failed: ${e.message}`);
    }
}

// ─── Search & Recommendations ─────────────────────────────────────────────────
function toggleSeedInput() {
    const s = document.getElementById('strategy').value;
    const g = document.getElementById('seed-group');
    const l = document.getElementById('seed-label');
    const i = document.getElementById('seed');
    const labels = {
        ARTIST_SIMILARITY: ['artist name:', 'radiohead'],
        TRACK_SIMILARITY: ['artist - track:', 'radiohead - paranoid android'],
        TAG_BASED: ['genre tag:', 'shoegaze']
    };
    if (labels[s]) {
        g.classList.remove('hidden');
        l.innerText = labels[s][0];
        i.placeholder = labels[s][1];
    } else {
        g.classList.add('hidden');
        i.value = '';
    }
}

function buildSearchUrl(s, seed, limit) {
    const enc = encodeURIComponent;
    const u = seed ? `&username=${enc(seed)}` : '';
    switch (s) {
        case 'USER_PERSONALIZED': return `/api/recommend/personal?limit=${limit}${u}`;
        case 'NOW_LISTENING': return `/api/recommend/now?limit=${limit}${u}`;
        case 'GENRE_BASED': return `/api/recommend/genres?limit=${limit}${u}`;
        case 'HYBRID': return `/api/recommend/hybrid?limit=${limit}${u}`;
        case 'TRACK_SIMILARITY':
            const sp = seed.indexOf(' - ');
            if (sp === -1) { alert('format: artist - track'); throw new Error('invalid format'); }
            return `/api/recommend/track?artist=${enc(seed.slice(0, sp).trim())}&track=${enc(seed.slice(sp + 3).trim())}&limit=${limit}`;
        case 'TAG_BASED': return `/api/recommend/tag?tag=${enc(seed)}&limit=${limit}`;
        default: return `/api/recommend?artist=${enc(seed)}&limit=${limit}`;
    }
}

async function searchRecommendations() {
    const strategy = document.getElementById('strategy').value;
    const seed = document.getElementById('seed').value.trim();
    const limit = parseInt(document.getElementById('limit').value) || 20;
    if (['ARTIST_SIMILARITY', 'TRACK_SIMILARITY', 'TAG_BASED'].includes(strategy) && !seed) {
        alert('enter a search term');
        return;
    }
    const btn = document.getElementById('search-btn');
    btn.disabled = true;
    btn.innerText = '[searching...]';
    const tableContainer = document.getElementById('results-table');
    document.getElementById('results-actions').classList.add('hidden');

    const frames = ['[⠋]', '[⠙]', '[⠹]', '[⠸]', '[⠼]', '[⠴]', '[⠦]', '[⠧]', '[⠇]', '[⠏]'];
    let idx = 0;
    tableContainer.innerHTML = `<div class="loading-pulse"><span id="search-spinner" class="spinner-frame">${frames[0]}</span> <span>searching tracks via ${escapeHtml(strategy.toLowerCase().replace(/_/g, ' '))}...</span></div>`;
    const spinTimer = setInterval(() => {
        idx = (idx + 1) % frames.length;
        const el = document.getElementById('search-spinner');
        if (el) el.innerText = frames[idx];
    }, 80);

    try {
        const res = await fetch(buildSearchUrl(strategy, seed, limit));
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.message || `http ${res.status}`);
        }
        recommendations = await res.json();
        renderResults(recommendations);
    } catch (e) {
        tableContainer.innerHTML = `<p style="color: var(--status-err);">search failed: ${escapeHtml(e.message)}</p>`;
    } finally {
        clearInterval(spinTimer);
        btn.disabled = false;
        btn.innerText = '[search]';
    }
}

function renderResults(recs) {
    const container = document.getElementById('results-table');
    const actions = document.getElementById('results-actions');
    if (!recs || recs.length === 0) {
        container.innerHTML = '<p style="color: var(--text-muted);">no recommendations found. check settings.</p>';
        actions.classList.add('hidden');
        return;
    }
    actions.classList.remove('hidden');
    let html = `<div class="table-container"><table><thead><tr>
        <th style="width: 40px; text-align: center;"><input type="checkbox" id="select-all-header" onchange="selectAll(this.checked)"></th>
        <th>artist</th><th>track</th><th>source</th><th>match</th><th>listeners</th>
    </tr></thead><tbody>`;
    recs.forEach((r, i) => {
        const t = r.track || {};
        html += `<tr>
            <td style="text-align: center;"><input type="checkbox" class="track-check" data-index="${i}" onchange="updateSelectedCount()"></td>
            <td style="font-weight: 600; color: var(--heading-color);">${escapeHtml(t.artist)}</td>
            <td>${escapeHtml(t.title)}</td>
            <td><span class="tag-pill">${escapeHtml(r.sourceArtist || 'algorithm')}</span></td>
            <td>${Math.round((r.matchScore || 0.5) * 100)}%</td>
            <td style="color: var(--text-muted);">${(r.listenerCount || 0).toLocaleString()}</td>
        </tr>`;
    });
    container.innerHTML = html + '</tbody></table></div>';
    updateSelectedCount();
}

function selectAll(checked) {
    document.querySelectorAll('.track-check').forEach(cb => cb.checked = checked);
    const h = document.getElementById('select-all-header');
    if (h) h.checked = checked;
    updateSelectedCount();
}

function updateSelectedCount() {
    const count = document.querySelectorAll('.track-check:checked').length;
    const label = document.getElementById('selected-count-label');
    if (label) label.innerText = `(${count} selected)`;
}

async function downloadSelected() {
    const selected = [];
    document.querySelectorAll('.track-check:checked').forEach(cb => {
        const r = recommendations[parseInt(cb.dataset.index)];
        if (r && r.track) selected.push({ artist: r.track.artist, title: r.track.title });
    });
    if (selected.length === 0) {
        alert('select at least one track');
        return;
    }
    const btn = document.getElementById('download-selected-btn');
    if (btn) {
        btn.disabled = true;
        btn.innerText = `[queuing ${selected.length}...]`;
    }
    startBatch(selected.length);
    try {
        const res = await fetch('/api/download/tracks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ tracks: selected })
        });
        if (!res.ok) throw new Error(`http ${res.status}`);
        if (btn) btn.innerText = '[downloading...]';
    } catch (e) {
        alert(`download failed: ${e.message}`);
        if (btn) {
            btn.disabled = false;
            btn.innerText = '[download selected]';
        }
        setDownloadStatus(false);
    }
}

// ─── Library & Cover Art ──────────────────────────────────────────────────────
async function refreshLibrary() {
    const list = document.getElementById('library-list');
    list.innerHTML = '<p style="color: var(--text-muted);">loading...</p>';
    try {
        const res = await fetch('/api/library');
        if (!res.ok) throw new Error(`http ${res.status}`);
        libraryData = await res.json();
        const count = document.getElementById('library-count');
        if (count) count.innerText = `(${libraryData.length} tracks)`;
        renderLibrary(libraryData);
    } catch (e) {
        list.innerHTML = `<p style="color: var(--status-err);">error: ${escapeHtml(e.message)}</p>`;
    }
}

function renderLibrary(files) {
    const list = document.getElementById('library-list');
    if (!files || files.length === 0) {
        list.innerHTML = '<p style="color: var(--text-muted);">no files downloaded yet.</p>';
        return;
    }
    list.innerHTML = '<ul>' + files.map(f => {
        const name = f.replace(/\\/g, '/').split('/').pop();
        const coverUrl = `/api/cover?file=${encodeURIComponent(f)}`;
        return `<li><img src="${coverUrl}" class="track-thumb" onerror="this.style.display='none'" alt="" /><span>${escapeHtml(name)}</span></li>`;
    }).join('') + '</ul>';
}

function filterLibrary() {
    const q = (document.getElementById('library-filter').value || '').toLowerCase();
    renderLibrary(libraryData.filter(f => f.toLowerCase().includes(q)));
}

// ─── Scheduled Pipeline Trigger ───────────────────────────────────────────────
async function triggerSchedule() {
    const btn = document.querySelector('button[onclick="triggerSchedule()"]');
    if (btn) {
        btn.disabled = true;
        btn.innerText = '[triggering...]';
    }
    setDownloadStatus(true, '[● auto-download active...]');
    try {
        const res = await fetch('/api/schedule/trigger', { method: 'POST' });
        if (!res.ok) throw new Error(`http ${res.status}`);
        logProgress('system: triggered scheduled download pipeline');
    } catch (e) {
        alert(`sync trigger failed: ${e.message}`);
        setDownloadStatus(false);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.innerText = '[auto-download now]';
        }
    }
}

// ─── HTML Utility ─────────────────────────────────────────────────────────────
function escapeHtml(str) {
    const d = document.createElement('div');
    d.textContent = str || '';
    return d.innerHTML;
}

// ─── Initialization ───────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
    initTheme();
    populateTimeDropdowns();
    connectWS();
    toggleSeedInput();
    openSettings();
    refreshLibrary();
});
