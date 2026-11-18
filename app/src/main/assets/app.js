const $ = id => document.getElementById(id);
const editor = $('editor');
const form = $('form');
let db;
let records = [];
let editing;
let photo;
let reading = false;
let busy = false;
let editorSession = 0;

function transaction(mode, action) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('checks', mode);
    const request = action(tx.objectStore('checks'));
    tx.oncomplete = () => resolve(request?.result);
    tx.onabort = () => reject(tx.error);
  });
}

async function refresh() {
  records = await transaction('readonly', store => store.getAll());
  records.sort((a, b) => String(b.date ?? '').localeCompare(String(a.date ?? '')));
  render();
}

function node(tag, content, className) {
  const element = document.createElement(tag);
  if (content !== undefined) element.textContent = content;
  if (className) element.className = className;
  return element;
}

const normalize = value => String(value ?? '').toLocaleLowerCase('fr').normalize('NFD').replace(/[\u0300-\u036f]/g, '');

function render() {
  const query = normalize($('search').value.trim());
  const filter = $('filter').value;
  const visible = records.filter(record =>
    (filter === 'TOUS' || record.status === filter) &&
    normalize(record.title + ' ' + (record.comment ?? '')).includes(query));
  $('count').textContent = visible.length + (visible.length === 1 ? ' contrôle' : ' contrôles');
  $('empty').hidden = visible.length > 0;
  $('empty').textContent = records.length ? 'Aucun résultat.' : 'Aucun contrôle. Appuie sur + pour en ajouter un.';
  $('list').replaceChildren(...visible.map(record => {
    const card = node('button', undefined, 'card');
    card.type = 'button';
    const picture = node(record.photo ? 'img' : 'span', undefined, 'card-image');
    if (record.photo) {
      picture.src = record.photo;
      picture.alt = '';
      picture.loading = 'lazy';
    }
    const body = node('span', undefined, 'card-body');
    const good = record.status === 'BON';
    body.append(node('span', good ? 'Bon' : 'Pas bon', good ? 'badge' : 'badge bad'),
      node('strong', record.title));
    const date = new Date(record.date);
    if (record.date && !isNaN(date)) body.append(node('time', date.toLocaleDateString('fr-FR')));
    if (record.comment) body.append(node('span', record.comment, 'note'));
    card.append(picture, body);
    card.onclick = () => openEditor(record);
    return card;
  }));
}

function showPhoto() {
  $('preview').hidden = !photo;
  $('photoPlaceholder').hidden = !!photo;
  if (photo) $('preview').src = photo;
  else $('preview').removeAttribute('src');
}

function error(message) {
  $('formError').textContent = message;
  $('formError').hidden = !message;
}

function setBusy(value) {
  busy = value;
  $('save').disabled = value || reading;
  $('delete').disabled = value;
  $('cancel').disabled = value;
  $('openCamera').disabled = value || reading;
  $('openGallery').disabled = value || reading;
}

function openEditor(record) {
  if (!db || editor.open) return;
  $('menu').open = false;
  editorSession++;
  reading = false;
  editing = record;
  photo = record?.photo;
  form.reset();
  $('formTitle').textContent = record ? 'Modifier le contrôle' : 'Ajouter un contrôle';
  $('title').value = record?.title ?? '';
  $('comment').value = record?.comment ?? '';
  form.elements.status.value = record?.status ?? 'BON';
  $('delete').hidden = !record;
  error('');
  setBusy(false);
  showPhoto();
  editor.showModal();
  editor.scrollTop = 0;
}

function readPhoto(file) {
  if (!file || !editor.open || busy) return;
  const session = editorSession;
  const reader = new FileReader();
  reading = true;
  error('');
  setBusy(false);
  reader.onloadend = () => {
    if (session !== editorSession || !editor.open) return;
    reading = false;
    if (typeof reader.result === 'string' && reader.result.startsWith('data:image/')) {
      photo = reader.result;
      showPhoto();
    } else {
      error('Impossible de lire cette photo.');
    }
    setBusy(false);
  };
  reader.readAsDataURL(file);
}

$('openCamera').onclick = () => $('camera').click();
$('openGallery').onclick = () => $('gallery').click();
for (const id of ['camera', 'gallery']) {
  $(id).onchange = event => {
    readPhoto(event.target.files[0]);
    event.target.value = '';
  };
}
$('add').onclick = () => openEditor();
$('cancel').onclick = () => { if (!busy) editor.close(); };
editor.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
$('search').oninput = render;
$('filter').onchange = render;
document.addEventListener('click', event => {
  if (!$('menu').contains(event.target)) $('menu').open = false;
});

async function saveChange(action) {
  setBusy(true);
  error('');
  try {
    await transaction('readwrite', action);
    editor.close();
  } catch {
    error('Impossible d’enregistrer la modification.');
    return;
  } finally {
    setBusy(false);
  }
  await refresh().catch(() => alert('Modification enregistrée. Rouvre l’application pour actualiser la liste.'));
}

form.onsubmit = async event => {
  event.preventDefault();
  if (busy || reading) return;
  const title = $('title').value.trim();
  if (!title || !photo) {
    error(!title ? 'Saisis un nom pour le contrôle.' : 'Prends ou sélectionne une photo.');
    return;
  }
  const record = {
    ...editing,
    id: editing?.id ?? crypto.randomUUID(),
    date: editing?.date ?? new Date().toISOString(),
    title,
    status: form.elements.status.value,
    comment: $('comment').value,
    photo
  };
  await saveChange(store => store.put(record));
};

$('delete').onclick = async () => {
  if (!editing || busy || !confirm('Supprimer ce contrôle et sa photo ?')) return;
  await saveChange(store => store.delete(editing.id));
};

$('export').onclick = async () => {
  if (!db) return;
  $('menu').open = false;
  try {
    const payload = JSON.stringify({version: 1, records: await transaction('readonly', store => store.getAll())});
    if (window.AndroidBridge) {
      window.AndroidBridge.saveBackup(payload);
      return;
    }
    const url = URL.createObjectURL(new Blob([payload], {type: 'application/json'}));
    const link = node('a');
    link.href = url;
    link.download = 'controle-photos.json';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 3000);
  } catch {
    alert('Impossible d’exporter la sauvegarde.');
  }
};

$('restore').onclick = () => {
  if (!db) return;
  $('menu').open = false;
  $('import').click();
};

$('import').onchange = async event => {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    if (!Array.isArray(data.records) || !data.records.every(record =>
      record && typeof record.id === 'string' && typeof record.title === 'string' &&
      ['BON', 'PAS BON'].includes(record.status) &&
      typeof record.photo === 'string' && record.photo.startsWith('data:image/'))) {
      throw new Error('Format de sauvegarde incorrect.');
    }
    if (!confirm(`Restaurer ${data.records.length} contrôles ? Les identifiants identiques seront remplacés.`)) return;
    await transaction('readwrite', store => {
      for (const record of data.records) store.put(record);
    });
    await refresh();
    alert('Restauration terminée.');
  } catch (failure) {
    alert('Restauration impossible. ' + failure.message);
  }
};

const request = indexedDB.open('controle-photos', 1);
request.onupgradeneeded = () => request.result.createObjectStore('checks', {keyPath: 'id'});
function storageError() {
  $('empty').textContent = 'Le stockage local est indisponible.';
}
request.onerror = storageError;
request.onsuccess = () => {
  db = request.result;
  refresh().catch(storageError);
};
