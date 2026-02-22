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
const reindexBtn = document.getElementById('reindexBtn');
const freezeBtn = document.getElementById('freezeBtn');

let currentState = {
  messageId: '',
  hasHtml: false,
  hasPlain: false,
  activeTab: 'plain',
};

function currentId() {
  const parts = window.location.pathname.split('/').filter(Boolean);
  return decodeURIComponent(parts[parts.length - 1] || '');
}

function escapeHtml(value) {
  return (value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
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
    const name = escapeHtml(att.filename || '(sem nome)');
    const inlineMark = att.isInline ? '<span class="badge">inline</span>' : '';
    const size = Number(att.size || 0);
    return `<li>
      <a href="/api/attachments/${encodeURIComponent(att.id)}/download">${name}</a>${inlineMark}
      <div>${size.toLocaleString('pt-BR')} bytes</div>
    </li>`;
  }).join('');
}

async function loadMessage() {
  clearStatus();
  const id = currentId();
  currentState.messageId = id;

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
}

tabHtmlEl.addEventListener('click', () => applyTab('html'));
tabPlainEl.addEventListener('click', () => applyTab('plain'));

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

loadMessage();
