const subjectEl = document.getElementById('subject');
const fromEl = document.getElementById('fromRaw');
const dateEl = document.getElementById('dateRaw');
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
const backToListBtn = document.getElementById('backToListBtn');
const prevMsgBtn = document.getElementById('prevMsgBtn');
const nextMsgBtn = document.getElementById('nextMsgBtn');
const reindexBtn = document.getElementById('reindexBtn');
const freezeBtn = document.getElementById('freezeBtn');

let currentState = {
  messageId: '',
  hasHtml: false,
  hasPlain: false,
  prevId: null,
  nextId: null,
  activeTab: 'plain',
};

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
  const response = await fetch(`/api/messages/${encodeURIComponent(id)}/render`);
  if (!response.ok) {
    return '';
  }
  const data = await response.json();
  return data.html || '';
}

async function loadAttachments(id) {
  attachmentsEl.innerHTML = '<li>Carregando anexos...</li>';
  const response = await fetch(`/api/messages/${encodeURIComponent(id)}/attachments`);
  if (!response.ok) {
    attachmentsEl.innerHTML = '<li>Falha ao carregar anexos.</li>';
    return;
  }

  const items = await response.json();
  if (!items || items.length === 0) {
    attachmentsEl.innerHTML = '<li>(sem anexos)</li>';
    return;
  }

  attachmentsEl.innerHTML = items.map((att) => {
    const name = (att.filename || '(sem nome)')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
    const inlineMark = att.isInline ? '<span class="badge">inline</span>' : '';
    const size = Number(att.size || 0);
    return `<li>
      <a href="/api/attachments/${encodeURIComponent(att.id)}/download">${name}</a>${inlineMark}
      <div>${size.toLocaleString('pt-BR')} bytes</div>
    </li>`;
  }).join('');
}

async function loadNeighbors(id) {
  currentState.prevId = null;
  currentState.nextId = null;
  updateNeighborButtons();

  const [prevResponse, nextResponse] = await Promise.all([
    fetch(`/api/messages/${encodeURIComponent(id)}/prev`),
    fetch(`/api/messages/${encodeURIComponent(id)}/next`),
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
  updateBackLink();

  subjectEl.textContent = 'Carregando mensagem...';
  fromEl.textContent = '-';
  dateEl.textContent = '-';
  messageIdEl.textContent = id || '-';
  panelHtmlEl.hidden = true;
  panelPlainEl.hidden = true;
  emptyBodyEl.hidden = false;

  const response = await fetch(`/api/messages/${encodeURIComponent(id)}`);
  if (!response.ok) {
    subjectEl.textContent = 'Mensagem nao encontrada';
    textEl.textContent = '';
    htmlContainerEl.innerHTML = '';
    attachmentsEl.innerHTML = '<li>(sem anexos)</li>';
    setStatus('error', 'Nao foi possivel carregar os dados da mensagem.');
    updateNeighborButtons();
    return;
  }

  const data = await response.json();
  subjectEl.textContent = data.subjectDisplay || data.subject || '(sem assunto)';
  fromEl.textContent = data.fromDisplay || data.fromRaw || '(sem remetente)';
  dateEl.textContent = data.dateRaw || '(sem data)';
  messageIdEl.textContent = data.messageId || id;
  textEl.textContent = data.textPlain || '';

  const renderedHtml = await loadRenderedHtml(id);
  htmlContainerEl.innerHTML = renderedHtml || '';

  currentState.hasHtml = Boolean(renderedHtml && renderedHtml.trim().length > 0);
  currentState.hasPlain = Boolean(data.textPlain && data.textPlain.trim().length > 0);

  if (currentState.hasHtml) {
    applyTab('html');
  } else if (currentState.hasPlain) {
    applyTab('plain');
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
  setStatus('info', 'Reindexando base de emails...');

  try {
    const response = await fetch('/api/index', { method: 'POST' });
    if (!response.ok) {
      setStatus('error', 'Falha ao reindexar.');
      return;
    }
    const data = await response.json();
    setStatus('ok', `Reindexacao concluida: inserted=${data.inserted}, updated=${data.updated}, skipped=${data.skipped}`);
    await loadMessage();
  } catch (_) {
    setStatus('error', 'Falha de rede ao reindexar.');
  } finally {
    setLoadingButtons(false);
  }
});

freezeBtn.addEventListener('click', async () => {
  const id = currentId();
  setLoadingButtons(true);
  setStatus('info', 'Congelando imagens remotas desta mensagem...');

  try {
    const response = await fetch(`/api/messages/${encodeURIComponent(id)}/freeze-assets`, { method: 'POST' });
    if (!response.ok) {
      setStatus('error', 'Falha ao congelar imagens.');
      return;
    }
    const data = await response.json();
    setStatus('ok', `Freeze concluido: downloaded=${data.downloaded}, failed=${data.failed}, skipped=${data.skipped}`);
    await loadMessage();
  } catch (_) {
    setStatus('error', 'Falha de rede ao congelar imagens.');
  } finally {
    setLoadingButtons(false);
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

loadMessage();
