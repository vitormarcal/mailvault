const subjectEl = document.getElementById('subject');
const fromEl = document.getElementById('fromRaw');
const dateHumanEl = document.getElementById('dateHuman');
const dateRawEl = document.getElementById('dateRaw');
const messageIdEl = document.getElementById('messageId');

const htmlContainerEl = document.getElementById('htmlContainer');
const textEl = document.getElementById('textPlain');
const panelHtmlEl = document.getElementById('panelHtml');
const panelPlainEl = document.getElementById('panelPlain');
const emptyBodyEl = document.getElementById('emptyBody');

const tabHtmlEl = document.getElementById('tabHtml');
const tabPlainEl = document.getElementById('tabPlain');

const attachmentsEl = document.getElementById('attachments');
const statusEl = document.getElementById('status');
const statusImagesEl = document.getElementById('statusImages');
const statusFreezeReasonEl = document.getElementById('statusFreezeReason');
const statusAttachmentsEl = document.getElementById('statusAttachments');
const statusSizeEl = document.getElementById('statusSize');
const statusFilePathEl = document.getElementById('statusFilePath');
const copyPathBtn = document.getElementById('copyPathBtn');
const backToListBtn = document.getElementById('backToListBtn');
const prevMsgBtn = document.getElementById('prevMsgBtn');
const nextMsgBtn = document.getElementById('nextMsgBtn');
const reindexBtn = document.getElementById('reindexBtn');
const freezeBtn = document.getElementById('freezeBtn');
const languageSelect = document.getElementById('languageSelect');
const freezeBtnDefaultText = freezeBtn.textContent;

const dateLabel = document.getElementById('dateLabel');
const rawDateLabel = document.getElementById('rawDateLabel');
const fromLabel = document.getElementById('fromLabel');
const idLabel = document.getElementById('idLabel');
const emptyBodyText = document.getElementById('emptyBodyText');
const statusEmailTitle = document.getElementById('statusEmailTitle');
const fileLabel = document.getElementById('fileLabel');
const attachmentsTitle = document.getElementById('attachmentsTitle');
const securityNotesTitle = document.getElementById('securityNotesTitle');
const securityTip1 = document.getElementById('securityTip1');
const securityTip2 = document.getElementById('securityTip2');

const I18N = {
  en: {
    pageTitle: 'MailVault - Message',
    backToInbox: 'Back to inbox',
    prev: 'Previous (k)',
    next: 'Next (j)',
    reindex: 'Reindex',
    freezeImages: 'Freeze images',
    loadingMessage: 'Loading message...',
    notFound: 'Message not found',
    fromFallback: '(no sender)',
    subjectFallback: '(no subject)',
    noDateRaw: '(no raw date)',
    noDate: '(no date)',
    dateLabel: 'Date',
    rawDateLabel: 'Raw date',
    fromLabel: 'De',
    idLabel: 'ID',
    safeHtml: 'Safe HTML',
    plainText: 'Plain text',
    emptyBody: 'This message has no text/plain or html body.',
    statusTitle: 'Email status',
    statusImages: 'Images: frozen {frozen}, failed {failed}, security blocked {securityBlocked}',
    statusFreezeReason: 'Freeze reason: {reason}',
    statusAttachments: 'Attachments: {count}',
    statusSize: 'Size: {size}',
    fileLabel: 'File:',
    copy: 'Copy',
    attachmentsTitle: 'Attachments',
    loadingAttachments: 'Loading attachments...',
    attachmentsLoadFailed: 'Failed to load attachments.',
    noAttachments: '(no attachments)',
    unnamedAttachment: '(unnamed)',
    bytes: 'bytes',
    inline: 'inline',
    securityNotes: 'Security notes',
    securityTip1: 'External links go through /go. Remote images do not auto-load.',
    securityTip2: 'Use "Freeze images" to download remote resources locally using security rules.',
    loadDetailsFailed: 'Could not load message details.',
    reindexRunning: 'Reindexing email store...',
    reindexQueued: 'Reindex accepted. Job {jobId} is running...',
    reindexFailed: 'Reindex failed.',
    reindexFailedWithReason: 'Reindex failed: {reason}',
    reindexNetworkFailed: 'Network failure while reindexing.',
    reindexDone: 'Reindex complete: inserted={inserted}, updated={updated}, skipped={skipped}',
    freezeRunning: 'Downloading remote images...',
    freezeFailed: 'Failed to freeze images.',
    freezeNetworkFailed: 'Network failure while freezing images.',
    freezeDone: '{downloaded} downloaded, {failed} failed, {skipped} skipped (of {total})',
    freezeIgnoredAction: 'Freeze is ignored for this message.',
    freezeFailuresPrefix: 'Failures',
    freezeButtonLoading: 'Downloading...',
    noPathToCopy: 'No file path to copy.',
    copiedPath: 'Path copied.',
    copyFailed: 'Failed to copy path.',
    sizeUnknown: '-',
    pathUnknown: '-',
  },
  'pt-BR': {
    pageTitle: 'MailVault - Mensagem',
    backToInbox: 'Voltar para caixa',
    prev: 'Anterior (k)',
    next: 'Proximo (j)',
    reindex: 'Reindexar',
    freezeImages: 'Congelar imagens',
    loadingMessage: 'Carregando mensagem...',
    notFound: 'Mensagem nao encontrada',
    fromFallback: '(sem remetente)',
    subjectFallback: '(sem assunto)',
    noDateRaw: '(sem data raw)',
    noDate: '(sem data)',
    dateLabel: 'Date',
    rawDateLabel: 'Raw date',
    fromLabel: 'From',
    idLabel: 'ID',
    safeHtml: 'HTML seguro',
    plainText: 'Texto puro',
    emptyBody: 'Esta mensagem nao possui corpo em text/plain ou html disponivel.',
    statusTitle: 'Status do email',
    statusImages: 'Imagens: congeladas {frozen}, falhas {failed}, bloqueadas por seguranca {securityBlocked}',
    statusFreezeReason: 'Motivo do freeze: {reason}',
    statusAttachments: 'Anexos: {count}',
    statusSize: 'Tamanho: {size}',
    fileLabel: 'Arquivo:',
    copy: 'Copiar',
    attachmentsTitle: 'Anexos',
    loadingAttachments: 'Carregando anexos...',
    attachmentsLoadFailed: 'Falha ao carregar anexos.',
    noAttachments: '(sem anexos)',
    unnamedAttachment: '(sem nome)',
    bytes: 'bytes',
    inline: 'inline',
    securityNotes: 'Notas de seguranca',
    securityTip1: 'Links externos passam por /go. Imagens remotas nao carregam automaticamente.',
    securityTip2: 'Use "Congelar imagens" para baixar recursos remotos localmente com as regras de seguranca.',
    loadDetailsFailed: 'Nao foi possivel carregar os dados da mensagem.',
    reindexRunning: 'Reindexando base de emails...',
    reindexQueued: 'Reindexacao aceita. Job {jobId} em execucao...',
    reindexFailed: 'Falha ao reindexar.',
    reindexFailedWithReason: 'Falha ao reindexar: {reason}',
    reindexNetworkFailed: 'Falha de rede ao reindexar.',
    reindexDone: 'Reindexacao concluida: inserted={inserted}, updated={updated}, skipped={skipped}',
    freezeRunning: 'Baixando imagens remotas...',
    freezeFailed: 'Falha ao congelar imagens.',
    freezeNetworkFailed: 'Falha de rede ao congelar imagens.',
    freezeDone: '{downloaded} baixadas, {failed} falharam, {skipped} ignoradas (de {total})',
    freezeIgnoredAction: 'Freeze esta ignorado para esta mensagem.',
    freezeFailuresPrefix: 'Falhas',
    freezeButtonLoading: 'Baixando...',
    noPathToCopy: 'Sem caminho de arquivo para copiar.',
    copiedPath: 'Caminho copiado.',
    copyFailed: 'Falha ao copiar caminho.',
    sizeUnknown: '-',
    pathUnknown: '-',
  },
};

let currentState = {
  messageId: '',
  hasHtml: false,
  hasPlain: false,
  prevId: null,
  nextId: null,
  activeTab: 'plain',
  language: 'en',
  freezeIgnored: false,
};
const INDEX_JOB_POLL_INTERVAL_MS = 1000;
const INDEX_JOB_POLL_ATTEMPTS = 300;

function t(key, vars = {}) {
  const bundle = I18N[currentState.language] || I18N.en;
  const template = bundle[key] ?? I18N.en[key] ?? key;
  return template.replaceAll(/\{(\w+)\}/g, (_, variable) => String(vars[variable] ?? ''));
}

function currentLocale() {
  return currentState.language === 'pt-BR' ? 'pt-BR' : 'en-US';
}

function apiFetch(input, init = {}) {
  return fetch(input, { credentials: 'same-origin', ...init });
}

function applyStaticTexts() {
  document.documentElement.lang = currentState.language;
  document.title = t('pageTitle');
  backToListBtn.textContent = t('backToInbox');
  prevMsgBtn.textContent = t('prev');
  nextMsgBtn.textContent = t('next');
  reindexBtn.textContent = t('reindex');
  freezeBtn.textContent = t('freezeImages');
  tabHtmlEl.textContent = t('safeHtml');
  tabPlainEl.textContent = t('plainText');
  dateLabel.textContent = t('dateLabel');
  rawDateLabel.textContent = t('rawDateLabel');
  fromLabel.textContent = t('fromLabel');
  idLabel.textContent = t('idLabel');
  emptyBodyText.textContent = t('emptyBody');
  statusEmailTitle.textContent = t('statusTitle');
  fileLabel.textContent = t('fileLabel');
  copyPathBtn.textContent = t('copy');
  attachmentsTitle.textContent = t('attachmentsTitle');
  securityNotesTitle.textContent = t('securityNotes');
  securityTip1.textContent = t('securityTip1');
  securityTip2.textContent = t('securityTip2');
  languageSelect.value = currentState.language;
}

async function loadLanguagePreference() {
  try {
    const response = await apiFetch('/api/ui/language');
    if (!response.ok) {
      currentState.language = 'en';
      return;
    }
    const data = await response.json();
    currentState.language = data.language === 'pt-BR' ? 'pt-BR' : 'en';
  } catch (_) {
    currentState.language = 'en';
  }
}

async function saveLanguagePreference(language) {
  currentState.language = language === 'pt-BR' ? 'pt-BR' : 'en';
  applyStaticTexts();
  await loadMessage();
  try {
    await apiFetch('/api/ui/language', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: currentState.language }),
    });
  } catch (_) {
    // Keep local language even if save fails.
  }
}

function currentId() {
  const parts = window.location.pathname.split('/').filter(Boolean);
  return decodeURIComponent(parts[parts.length - 1] || '');
}

function setStatus(kind, message) {
  statusEl.className = `status show ${kind}`;
  statusEl.textContent = message;
}

function clearStatus() {
  statusEl.className = 'status';
  statusEl.textContent = '';
}

function setLoadingButtons(isLoading) {
  reindexBtn.disabled = isLoading;
  freezeBtn.disabled = isLoading;
}

function setFreezeLoading(isLoading) {
  freezeBtn.disabled = isLoading;
  freezeBtn.textContent = isLoading ? t('freezeButtonLoading') : t('freezeImages');
}

function formatBytes(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) {
    return t('sizeUnknown');
  }
  if (value < 1024) {
    return `${value} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let current = value / 1024;
  let idx = 0;
  while (current >= 1024 && idx < units.length - 1) {
    current /= 1024;
    idx += 1;
  }
  return `${current.toFixed(1)} ${units[idx]} (${value.toLocaleString(currentLocale())} B)`;
}

function resolveListQuery() {
  const params = new URLSearchParams(window.location.search);
  const returnParam = params.get('return');
  if (returnParam && returnParam.startsWith('?')) {
    return returnParam;
  }
  const stored = window.sessionStorage.getItem('mailvault:lastListQuery');
  if (stored && stored.startsWith('?')) {
    return stored;
  }
  return '';
}

function listUrl() {
  const listQuery = resolveListQuery();
  return listQuery ? `/${listQuery}` : '/';
}

function updateBackLink() {
  backToListBtn.href = listUrl();
}

function updateNeighborButtons() {
  prevMsgBtn.disabled = !currentState.prevId;
  nextMsgBtn.disabled = !currentState.nextId;
}

function gotoMessageById(id) {
  if (!id) {
    return;
  }
  const listQuery = resolveListQuery();
  const params = new URLSearchParams();
  if (listQuery) {
    params.set('return', listQuery);
  }
  const suffix = params.toString();
  window.location.href = `/messages/${encodeURIComponent(id)}${suffix ? `?${suffix}` : ''}`;
}

function normalizeTimezoneLabel(label) {
  if (!label) {
    return '';
  }
  const normalized = label.replace('GMT', 'UTC').replace(/\s+/g, ' ').trim();
  return normalized.length <= 10 ? normalized : normalized.slice(0, 10);
}

function formatHumanDate(epochMs) {
  const numeric = Number(epochMs);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return t('noDate');
  }
  const date = new Date(numeric);
  const main = new Intl.DateTimeFormat(currentLocale(), {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
  const timezoneRaw = new Intl.DateTimeFormat(currentLocale(), { timeZoneName: 'shortOffset' }).formatToParts(date)
    .find((part) => part.type === 'timeZoneName')?.value;
  const timezone = normalizeTimezoneLabel(timezoneRaw);
  return timezone ? `${main} (${timezone})` : main;
}

function applyTab(tab) {
  currentState.activeTab = tab;
  tabHtmlEl.classList.toggle('active', tab === 'html');
  tabPlainEl.classList.toggle('active', tab === 'plain');

  const showHtml = tab === 'html' && currentState.hasHtml;
  const showPlain = tab === 'plain' && currentState.hasPlain;

  panelHtmlEl.hidden = !showHtml;
  panelPlainEl.hidden = !showPlain;
  emptyBodyEl.hidden = showHtml || showPlain;
}

async function loadRenderedHtml(id) {
  const response = await apiFetch(`/api/messages/${encodeURIComponent(id)}/render`);
  if (!response.ok) {
    return '';
  }
  const data = await response.json();
  return data.html || '';
}

async function loadAttachments(id) {
  attachmentsEl.innerHTML = `<li>${t('loadingAttachments')}</li>`;
  const response = await apiFetch(`/api/messages/${encodeURIComponent(id)}/attachments`);
  if (!response.ok) {
    attachmentsEl.innerHTML = `<li>${t('attachmentsLoadFailed')}</li>`;
    return;
  }

  const items = await response.json();
  if (!items || items.length === 0) {
    attachmentsEl.innerHTML = `<li>${t('noAttachments')}</li>`;
    return;
  }

  attachmentsEl.innerHTML = items.map((att) => {
    const name = (att.filename || t('unnamedAttachment'))
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
    const inlineMark = att.isInline ? `<span class="badge">${t('inline')}</span>` : '';
    const size = Number(att.size || 0);
    return `<li>
      <a href="/api/attachments/${encodeURIComponent(att.id)}/download">${name}</a>${inlineMark}
      <div>${size.toLocaleString(currentLocale())} ${t('bytes')}</div>
    </li>`;
  }).join('');
}

async function loadNeighbors(id) {
  currentState.prevId = null;
  currentState.nextId = null;
  updateNeighborButtons();

  const [prevResponse, nextResponse] = await Promise.all([
    apiFetch(`/api/messages/${encodeURIComponent(id)}/prev`),
    apiFetch(`/api/messages/${encodeURIComponent(id)}/next`),
  ]);

  if (prevResponse.ok) {
    const prevData = await prevResponse.json();
    currentState.prevId = prevData.id || null;
  }
  if (nextResponse.ok) {
    const nextData = await nextResponse.json();
    currentState.nextId = nextData.id || null;
  }

  updateNeighborButtons();
}

async function loadMessage() {
  clearStatus();
  const id = currentId();
  currentState.messageId = id;
  currentState.freezeIgnored = false;
  updateBackLink();

  subjectEl.textContent = t('loadingMessage');
  fromEl.textContent = '-';
  dateHumanEl.textContent = '-';
  dateRawEl.textContent = '-';
  messageIdEl.textContent = id || '-';
  panelHtmlEl.hidden = true;
  panelPlainEl.hidden = true;
  emptyBodyEl.hidden = false;

  const response = await apiFetch(`/api/messages/${encodeURIComponent(id)}`);
  if (!response.ok) {
    subjectEl.textContent = t('notFound');
    textEl.textContent = '';
    htmlContainerEl.innerHTML = '';
    attachmentsEl.innerHTML = `<li>${t('noAttachments')}</li>`;
    statusImagesEl.textContent = t('statusImages', { frozen: '-', failed: '-', securityBlocked: '-' });
    statusFreezeReasonEl.textContent = t('statusFreezeReason', { reason: '-' });
    statusAttachmentsEl.textContent = t('statusAttachments', { count: '-' });
    statusSizeEl.textContent = t('statusSize', { size: t('sizeUnknown') });
    statusFilePathEl.textContent = t('pathUnknown');
    setStatus('error', t('loadDetailsFailed'));
    updateNeighborButtons();
    return;
  }

  const data = await response.json();
  subjectEl.textContent = data.subjectDisplay || data.subject || t('subjectFallback');
  fromEl.textContent = data.fromDisplay || data.fromRaw || t('fromFallback');
  dateHumanEl.textContent = formatHumanDate(data.dateEpoch ?? data.fileMtimeEpoch);
  dateRawEl.textContent = data.dateRaw || t('noDateRaw');
  messageIdEl.textContent = data.messageId || id;
  textEl.textContent = data.textPlain || '';
  statusImagesEl.textContent = t('statusImages', {
    frozen: Number(data.frozenAssetsCount || 0),
    failed: Number(data.assetsFailedCount || 0),
    securityBlocked: Number(data.securitySkippedCount || 0),
  });
  statusFreezeReasonEl.textContent = t('statusFreezeReason', { reason: data.freezeLastReason || '-' });
  statusAttachmentsEl.textContent = t('statusAttachments', { count: Number(data.attachmentsCount || 0) });
  statusSizeEl.textContent = t('statusSize', { size: formatBytes(data.messageSizeBytes ?? data.fileSize) });
  statusFilePathEl.textContent = data.filePath || t('pathUnknown');
  currentState.freezeIgnored = Boolean(data.freezeIgnored);
  freezeBtn.disabled = currentState.freezeIgnored;

  const renderedHtml = await loadRenderedHtml(id);
  htmlContainerEl.innerHTML = renderedHtml || '';

  currentState.hasHtml = Boolean(renderedHtml && renderedHtml.trim().length > 0);
  currentState.hasPlain = Boolean(data.textPlain && data.textPlain.trim().length > 0);

  if (currentState.hasHtml) {
    applyTab('html');
  } else {
    applyTab('plain');
  }

  await loadAttachments(id);
  await loadNeighbors(id);
}

tabHtmlEl.addEventListener('click', () => applyTab('html'));
tabPlainEl.addEventListener('click', () => applyTab('plain'));
prevMsgBtn.addEventListener('click', () => gotoMessageById(currentState.prevId));
nextMsgBtn.addEventListener('click', () => gotoMessageById(currentState.nextId));

reindexBtn.addEventListener('click', async () => {
  setLoadingButtons(true);
  setStatus('info', t('reindexRunning'));

  try {
    const startResponse = await apiFetch('/api/index', { method: 'POST' });
    if (!startResponse.ok) {
      setStatus('error', t('reindexFailed'));
      return;
    }
    const started = await startResponse.json();
    const jobId = started.jobId;
    if (!jobId) {
      setStatus('error', t('reindexFailed'));
      return;
    }
    setStatus('info', t('reindexQueued', { jobId }));
    const data = await waitForIndexJob(jobId);
    if (data.status !== 'SUCCEEDED' || !data.result) {
      setStatus('error', t('reindexFailedWithReason', { reason: data.error || t('reindexFailed') }));
      return;
    }
    setStatus('ok', t('reindexDone', {
      inserted: data.result.inserted,
      updated: data.result.updated,
      skipped: data.result.skipped,
    }));
    await loadMessage();
  } catch (_) {
    setStatus('error', t('reindexNetworkFailed'));
  } finally {
    setLoadingButtons(false);
  }
});

async function waitForIndexJob(jobId) {
  for (let attempt = 0; attempt < INDEX_JOB_POLL_ATTEMPTS; attempt += 1) {
    const response = await apiFetch(`/api/index/jobs/${encodeURIComponent(jobId)}`);
    if (!response.ok) {
      return { status: 'FAILED', error: `status lookup failed (${response.status})` };
    }
    const data = await response.json();
    if (data.status !== 'RUNNING') {
      return data;
    }
    await new Promise((resolve) => window.setTimeout(resolve, INDEX_JOB_POLL_INTERVAL_MS));
  }
  return { status: 'FAILED', error: 'status polling timeout' };
}

freezeBtn.addEventListener('click', async () => {
  if (currentState.freezeIgnored) {
    setStatus('info', t('freezeIgnoredAction'));
    return;
  }
  const id = currentId();
  setFreezeLoading(true);
  setStatus('info', t('freezeRunning'));

  try {
    const response = await apiFetch(`/api/messages/${encodeURIComponent(id)}/freeze-assets`, { method: 'POST' });
    if (!response.ok) {
      setStatus('error', t('freezeFailed'));
      return;
    }
    const data = await response.json();
    const summary = t('freezeDone', {
      downloaded: data.downloaded || 0,
      failed: data.failed || 0,
      skipped: data.skipped || 0,
      total: data.totalFound || 0,
    });
    if (Array.isArray(data.failures) && data.failures.length > 0) {
      const details = data.failures
        .slice(0, 3)
        .map((item) => `${item.host}: ${item.reason} (${item.count})`)
        .join(' | ');
      setStatus('ok', `${summary}. ${t('freezeFailuresPrefix')}: ${details}`);
    } else {
      setStatus('ok', summary);
    }
    await loadMessage();
  } catch (_) {
    setStatus('error', t('freezeNetworkFailed'));
  } finally {
    setFreezeLoading(false);
  }
});

document.addEventListener('keydown', (event) => {
  if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) {
    return;
  }

  const target = event.target;
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement ||
    (target instanceof HTMLElement && target.isContentEditable)
  ) {
    return;
  }

  if (event.key === 'j') {
    event.preventDefault();
    gotoMessageById(currentState.nextId);
  } else if (event.key === 'k') {
    event.preventDefault();
    gotoMessageById(currentState.prevId);
  } else if (event.key === 'g') {
    event.preventDefault();
    window.location.href = listUrl();
  }
});

copyPathBtn.addEventListener('click', async () => {
  const path = statusFilePathEl.textContent || '';
  if (!path || path === '-') {
    setStatus('error', t('noPathToCopy'));
    return;
  }
  try {
    await navigator.clipboard.writeText(path);
    setStatus('ok', t('copiedPath'));
  } catch (_) {
    setStatus('error', t('copyFailed'));
  }
});

languageSelect.addEventListener('change', () => {
  saveLanguagePreference(languageSelect.value);
});

async function initPage() {
  await loadLanguagePreference();
  applyStaticTexts();
  await loadMessage();
}

initPage();
