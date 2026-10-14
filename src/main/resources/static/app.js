const $ = (id) => document.getElementById(id);
const page = document.body.dataset.page;
const params = new URLSearchParams(location.search);
const folderId = params.get("cartella") || null;
const allFiles = params.get("vista") === "tutti";
let csrf,
  documents = [],
  folders = [],
  user,
  editingFolder = null,
  editingDocument = null,
  deletingFolder = null;
let queue = [],
  dragged = null;
const drawings = {
  archive: '<path d="M3 7h18v13H3zM2 3h20v4H2zM9 11h6"/>',
  folder: '<path d="M3 7V5h6l2 3h10v12H3z"/>',
  "folder-plus": '<path d="M3 7V5h6l2 3h10v12H3zM12 11v6M9 14h6"/>',
  files: '<path d="M8 3h9l4 4v14H8zM17 3v5h4M4 7H2v14h3M11 12h6M11 16h6"/>',
  file: '<path d="M5 3h9l5 5v13H5zM14 3v5h5M8 12h8M8 16h6"/>',
  convert:
    '<path d="M4 7h14l-3-3M20 17H6l3 3M18 7l-3 3M6 17l3-3M3 10v4M21 10v4"/>',
  merge: '<path d="M6 3v4c0 4 6 3 6 7v7M18 3v4c0 4-6 3-6 7M8 17l4 4 4-4"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  upload: '<path d="M12 16V3M7 8l5-5 5 5M4 14v6h16v-6"/>',
  download: '<path d="M12 3v13M7 11l5 5 5-5M4 17v4h16v-4"/>',
  "arrow-right": '<path d="M4 12h16M14 6l6 6-6 6"/>',
  "arrow-left": '<path d="M20 12H4M10 6l-6 6 6 6"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  logout: '<path d="M9 4H4v16h5M12 12h9M17 8l4 4-4 4"/>',
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6zM8 12l3 3 5-6"/>',
  database:
    '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  edit: '<path d="m15 4 5 5-10 10-6 1 1-6zM12 7l5 5"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  refresh: '<path d="M20 9a8 8 0 1 0 0 6M20 3v6h-6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
  up: '<path d="M12 20V4M6 10l6-6 6 6"/>',
  down: '<path d="M12 4v16M6 14l6 6 6-6"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
};
const icon = (name) =>
  `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${drawings[name] || drawings.file}</svg>`;
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
function icons(root = document) {
  root.querySelectorAll("[data-icon]").forEach((el) => {
    el.innerHTML = icon(el.dataset.icon);
  });
}
function notice(message, error = false) {
  const el = $("notice");
  el.hidden = false;
  el.classList.toggle("error", error);
  el.textContent = message;
}
function report(error, dialog) {
  if (dialog?.open) {
    let el = dialog.querySelector(".dialog-error");
    if (!el) {
      el = document.createElement("p");
      el.className = "dialog-error";
      el.setAttribute("role", "alert");
      dialog.append(el);
    }
    el.textContent = error.message;
  } else notice(error.message, true);
}
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (options.method && options.method !== "GET") {
    if (!csrf) csrf = await (await fetch("/api/auth/csrf")).json();
    headers[csrf.headerName] = csrf.token;
  }
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    let data;
    try {
      data = await response.json();
    } catch {}
    if (response.status === 401 && page !== "login")
      location.replace(
        "/accesso?next=" +
          encodeURIComponent(location.pathname + location.search),
      );
    throw Error(
      data?.message ||
        (response.status === 413
          ? "I file superano il limite di caricamento consentito."
          : `Richiesta fallita (${response.status})`),
    );
  }
  return response.status === 204 ? null : response.json();
}
const jsonRequest = (method, body) => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
const bytes = (n) =>
  n >= 1024 * 1024
    ? `${(n / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(0, Math.round(n / 1024))} KB`;
const archiveUrl = (id) =>
  id ? "/archivio?cartella=" + encodeURIComponent(id) : "/archivio";
function folderPath(id) {
  const parts = [],
    seen = new Set();
  while (id && !seen.has(id)) {
    seen.add(id);
    const f = folders.find((f) => f.id === id);
    if (!f) break;
    parts.unshift(f.name);
    id = f.parentId;
  }
  return parts.join(" / ") || "Il mio archivio";
}
function destinationOptions(select, selected = null, exclude = null) {
  const excluded = new Set(exclude ? [exclude] : []);
  let added = true;
  while (added) {
    added = false;
    for (const f of folders)
      if (excluded.has(f.parentId) && !excluded.has(f.id)) {
        excluded.add(f.id);
        added = true;
      }
  }
  select.replaceChildren(new Option("Il mio archivio", ""));
  for (const f of [...folders].sort((a, b) =>
    folderPath(a.id).localeCompare(folderPath(b.id), "it"),
  ))
    if (!excluded.has(f.id)) select.add(new Option(folderPath(f.id), f.id));
  select.value = selected || "";
}
function shell() {
  const labels = {
    archive: "Archivio",
    convert: "Converti e carica",
    merge: "Unisci PDF",
  };
  $("sidebar").innerHTML =
    `<a href="/archivio" class="sidebar-brand"><img src="/assets/avelspace-logo.png" width="48" height="48" alt="Logo AvelSpace"><span class="brand-type">Avel<span>Space</span><small>DOCS / WORKSPACE</small></span></a><div class="workspace-tag"><span class="status-dot"></span>SPAZIO PERSONALE</div><div class="nav-label">WORKSPACE</div><nav class="sidebar-nav" aria-label="Navigazione principale"><a class="nav-link ${page === "archive" ? "active" : ""}" href="/archivio">${icon("archive")}Archivio<span id="nav-document-count" class="nav-count">0</span></a><a class="nav-link ${page === "convert" ? "active" : ""}" href="/converti">${icon("convert")}Converti e carica</a><a class="nav-link ${page === "merge" ? "active" : ""}" href="/unisci">${icon("merge")}Unisci PDF</a></nav><div class="sidebar-folders"><div class="nav-label">LE TUE CARTELLE</div><div id="nav-folders"></div><a class="sidebar-folder" href="/archivio?vista=tutti">${icon("files")}Tutti i documenti</a></div><div class="sidebar-bottom"><div class="private-space">${icon("shield")}<div><strong>Il tuo spazio è privato</strong>Documenti associati al tuo account</div></div><div class="sidebar-version">AVELSPACE · DOCUMENT WORKSPACE</div></div>`;
  $("topbar").innerHTML =
    `<button class="icon-button mobile-menu" id="mobile-menu" aria-label="Apri navigazione" aria-expanded="false">${icon("menu")}</button><div class="topbar-path"><span>Workspace</span>${icon("chevron")}<strong>${labels[page]}</strong></div><div class="topbar-account"><span class="account-avatar">${esc(user.username.slice(0, 2).toUpperCase())}</span><span class="account-name">${esc(user.username)}</span><button class="icon-button" id="logout" aria-label="Esci dal tuo account" title="Esci">${icon("logout")}</button></div>`;
  $("logout").onclick = async () => {
    try {
      await api("/api/auth/logout", { method: "POST" });
      location.assign("/accesso");
    } catch (e) {
      report(e);
    }
  };
  $("mobile-menu").onclick = () => {
    const open = $("sidebar").classList.toggle("open");
    $("mobile-menu").setAttribute("aria-expanded", String(open));
  };
  document.addEventListener("click", (e) => {
    if (
      $("sidebar").classList.contains("open") &&
      !$("sidebar").contains(e.target) &&
      !$("mobile-menu").contains(e.target)
    ) {
      $("sidebar").classList.remove("open");
      $("mobile-menu").setAttribute("aria-expanded", "false");
    }
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      $("sidebar").classList.remove("open");
      $("mobile-menu").setAttribute("aria-expanded", "false");
    }
  });
  icons();
}
function renderNavigation() {
  $("nav-document-count").textContent = documents.length;
  const nav = $("nav-folders");
  nav.replaceChildren();
  function children(parent, depth = 0) {
    for (const f of folders.filter((f) => f.parentId === parent)) {
      const a = document.createElement("a");
      a.className = `sidebar-folder ${depth ? "nested" : ""} ${depth > 1 ? "nested-2" : ""} ${folderId === f.id ? "selected" : ""}`;
      a.href = archiveUrl(f.id);
      a.innerHTML = icon("folder") + `<span>${esc(f.name)}</span>`;
      nav.append(a);
      if (depth < 30) children(f.id, depth + 1);
    }
  }
  children(null);
  if (!folders.length)
    nav.innerHTML =
      '<div class="folder-placeholder">Un posto per ogni progetto.</div>';
}
async function reloadData() {
  [documents, folders] = await Promise.all([
    api("/api/conversions"),
    api("/api/folders"),
  ]);
  renderNavigation();
}
function modal(dialog) {
  dialog.querySelector(".dialog-error")?.remove();
  dialog.showModal();
}
function commonDialogs() {
  document
    .querySelectorAll(".dialog-close")
    .forEach((b) => (b.onclick = () => b.closest("dialog").close()));
  $("folder-form").onsubmit = async (e) => {
    e.preventDefault();
    $("folder-save").disabled = true;
    try {
      const req = {
        name: $("folder-name").value,
        parentId: $("folder-parent").value || null,
      };
      await api(
        "/api/folders" +
          (editingFolder ? "/" + encodeURIComponent(editingFolder.id) : ""),
        jsonRequest(editingFolder ? "PUT" : "POST", req),
      );
      $("folder-dialog").close();
      await reloadData();
      if (page === "archive") renderArchive();
      else refreshDestinations();
      notice(
        editingFolder
          ? "Cartella aggiornata."
          : "La tua nuova cartella è pronta.",
      );
    } catch (e) {
      report(e, $("folder-dialog"));
    } finally {
      $("folder-save").disabled = false;
    }
  };
  $("document-form").onsubmit = async (e) => {
    e.preventDefault();
    $("document-save").disabled = true;
    try {
      await api(
        "/api/conversions/" + encodeURIComponent(editingDocument.id),
        jsonRequest("PATCH", {
          displayName: $("document-name").value,
          folderId: $("document-folder").value || null,
        }),
      );
      $("document-dialog").close();
      await reloadData();
      renderArchive();
      notice("Nome e posizione del documento aggiornati.");
    } catch (e) {
      report(e, $("document-dialog"));
    } finally {
      $("document-save").disabled = false;
    }
  };
  $("delete-form").onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api("/api/folders/" + encodeURIComponent(deletingFolder.id), {
        method: "DELETE",
      });
      $("delete-dialog").close();
      if (deletingFolder.id === folderId) {
        location.assign(archiveUrl(deletingFolder.parentId));
        return;
      }
      await reloadData();
      renderArchive();
      notice("Cartella vuota eliminata.");
    } catch (e) {
      report(e, $("delete-dialog"));
    }
  };
}
function editFolder(folder = null) {
  editingFolder = folder;
  $("folder-dialog-title").textContent = folder
    ? "Modifica cartella"
    : "Nuova cartella";
  $("folder-save").textContent = folder ? "Salva modifiche" : "Crea cartella";
  $("folder-name").value = folder?.name || "";
  destinationOptions(
    $("folder-parent"),
    folder ? folder.parentId : folderId,
    folder?.id,
  );
  modal($("folder-dialog"));
}
function renderArchive() {
  $("total-files").textContent = documents.length;
  $("total-folders").textContent = folders.length;
  $("total-storage").textContent = bytes(
    documents.reduce(
      (n, d) => n + d.size + (d.format === "PDF" ? 0 : d.pdfSize),
      0,
    ),
  );
  const current = folders.find((f) => f.id === folderId);
  $("page-title").textContent = allFiles
    ? "Tutti i documenti"
    : current?.name || "Il tuo archivio";
  $("page-description").textContent = current
    ? "Uno spazio dedicato ai documenti di " + current.name + "."
    : "Tutti i tuoi documenti. Una casa per ogni progetto.";
  for (const id of ["new-file", "empty-upload"])
    $(id).href =
      "/converti" +
      (folderId ? "?cartella=" + encodeURIComponent(folderId) : "");
  $("edit-current-folder").hidden = !current;
  $("edit-current-folder").onclick = () => editFolder(current);
  const chain = [];
  let parent = current;
  const seen = new Set();
  while (parent && !seen.has(parent.id)) {
    seen.add(parent.id);
    chain.unshift(parent);
    parent = folders.find((f) => f.id === parent.parentId);
  }
  $("breadcrumbs").innerHTML =
    `<a href="/archivio">${icon("archive")} Il mio archivio</a>` +
    chain
      .map(
        (f, i) =>
          icon("chevron") +
          (i === chain.length - 1
            ? `<strong>${esc(f.name)}</strong>`
            : `<a href="${archiveUrl(f.id)}">${esc(f.name)}</a>`),
      )
      .join("") +
    (allFiles ? icon("chevron") + "<strong>Tutti i documenti</strong>" : "");
  $("folder-section").hidden = allFiles;
  const children = folders.filter((f) => f.parentId === folderId);
  $("folder-count").textContent = children.length;
  $("folder-grid").innerHTML = children
    .map(
      (f) =>
        `<article class="folder-card"><a href="${archiveUrl(f.id)}" class="folder-link"><span class="folder-symbol">${icon("folder")}</span><span class="folder-name">${esc(f.name)}</span><div class="folder-meta">${documents.filter((d) => d.folderId === f.id).length} file · ${folders.filter((x) => x.parentId === f.id).length} sottocartelle</div></a><div class="folder-menu"><button class="icon-button" data-edit-folder="${esc(f.id)}" aria-label="Modifica ${esc(f.name)}" title="Modifica cartella">${icon("edit")}</button><button class="icon-button" data-delete-folder="${esc(f.id)}" aria-label="Elimina ${esc(f.name)}" title="Elimina cartella vuota">${icon("trash")}</button></div></article>`,
    )
    .join("");
  if (!children.length)
    $("folder-grid").innerHTML =
      '<p class="no-folders">Crea una cartella per organizzare il prossimo progetto.</p>';
  $("folder-grid")
    .querySelectorAll("[data-edit-folder]")
    .forEach(
      (b) =>
        (b.onclick = () =>
          editFolder(folders.find((f) => f.id === b.dataset.editFolder))),
    );
  $("folder-grid")
    .querySelectorAll("[data-delete-folder]")
    .forEach(
      (b) =>
        (b.onclick = () => {
          deletingFolder = folders.find((f) => f.id === b.dataset.deleteFolder);
          $("delete-message").textContent =
            `Vuoi eliminare la cartella “${deletingFolder.name}”?`;
          modal($("delete-dialog"));
        }),
    );
  renderDocuments();
}
function renderDocuments() {
  const search = $("search").value.toLocaleLowerCase("it"),
    kind = $("kind-filter").value;
  const scoped = documents.filter((d) => allFiles || d.folderId === folderId);
  const visible = scoped.filter(
    (d) =>
      (!kind || d.kind === kind) &&
      `${d.displayName} ${d.name}`.toLocaleLowerCase("it").includes(search),
  );
  $("count").textContent = scoped.length;
  $("empty").hidden = visible.length > 0;
  $("history").hidden = !visible.length;
  $("empty").querySelector("h3").textContent = scoped.length
    ? "Nessun documento trovato"
    : "Il tuo prossimo documento parte da qui";
  $("empty").querySelector("p").textContent = scoped.length
    ? "Prova un nome diverso o cambia il filtro."
    : "Carica un file o crea una cartella per dare ordine al tuo spazio.";
  $("rows").innerHTML = visible
    .map((d) => {
      const date = new Date(d.created),
        title = d.displayName || d.name,
        kindLabel =
          {
            CONVERSION: "Convertito",
            UPLOAD: "PDF caricato",
            MERGE: "PDF unito",
          }[d.kind] || "Convertito";
      return `<tr><td><div class="document-title-cell"><span class="document-icon ${d.kind === "MERGE" ? "merge" : d.format.toLowerCase()}">${icon(d.kind === "MERGE" ? "merge" : "file")}</span><div><div class="doc-name">${esc(title)}</div><div class="doc-meta">${esc(d.format)} ${d.format === "PDF" ? "" : "→ PDF"} · ${bytes(d.pdfSize)}${d.pages ? " · " + d.pages + " pagine" : ""}${allFiles ? " · " + esc(folderPath(d.folderId)) : ""}</div></div></div></td><td><span class="kind-pill">${icon(d.kind === "MERGE" ? "merge" : d.kind === "UPLOAD" ? "upload" : "convert")}${kindLabel}</span></td><td class="date-cell">${date.toLocaleDateString("it-IT", { day: "numeric", month: "short", year: "numeric" })}<small>${date.toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" })}</small></td><td><div class="file-actions">${d.format !== "PDF" ? `<a class="download-original" href="/api/conversions/${encodeURIComponent(d.id)}/original" title="Scarica originale">${esc(d.format)}</a>` : ""}<a class="download-pdf" href="/api/conversions/${encodeURIComponent(d.id)}/pdf">${icon("download")}PDF</a><button class="icon-button" data-edit-document="${esc(d.id)}" aria-label="Organizza ${esc(title)}" title="Rinomina o sposta">${icon("edit")}</button><a class="icon-button" href="/unisci?file=${encodeURIComponent(d.id)}" title="Aggiungi a un’unione" aria-label="Unisci ${esc(title)}">${icon("merge")}</a></div></td></tr>`;
    })
    .join("");
  $("visible-summary").textContent =
    `${visible.length} ${visible.length === 1 ? "documento" : "documenti"}${visible.length !== scoped.length ? " di " + scoped.length : ""} ${allFiles ? "nel tuo spazio" : "in questa cartella"}`;
  $("rows")
    .querySelectorAll("[data-edit-document]")
    .forEach(
      (b) =>
        (b.onclick = () => {
          editingDocument = documents.find(
            (d) => d.id === b.dataset.editDocument,
          );
          $("document-name").value =
            editingDocument.displayName || editingDocument.name;
          destinationOptions($("document-folder"), editingDocument.folderId);
          modal($("document-dialog"));
        }),
    );
}
function refreshDestinations() {
  if (page === "convert")
    destinationOptions(
      $("upload-folder"),
      $("upload-folder").value || folderId,
    );
  if (page === "merge")
    destinationOptions($("merge-folder"), $("merge-folder").value || folderId);
}
function bindDrop(element, onFiles) {
  for (const name of ["dragenter", "dragover"])
    element.addEventListener(name, (e) => {
      e.preventDefault();
      element.classList.add("drag");
    });
  for (const name of ["dragleave", "drop"])
    element.addEventListener(name, (e) => {
      e.preventDefault();
      element.classList.remove("drag");
      if (name === "drop" && e.dataTransfer.files.length)
        onFiles([...e.dataTransfer.files]);
    });
}
function download(id) {
  const a = document.createElement("a");
  a.href = "/api/conversions/" + encodeURIComponent(id) + "/pdf";
  a.download = "";
  document.body.append(a);
  a.click();
  a.remove();
}
function result(element, document, label) {
  element.hidden = false;
  element.innerHTML = `<div class="result-title">${icon("check")}<div><strong>${esc(label)} · ${esc(document.displayName)}</strong><p>${document.pages ? document.pages + " pagine · " : ""}${bytes(document.pdfSize)} · ${esc(folderPath(document.folderId))}</p></div></div><div class="result-actions"><a class="secondary" href="${archiveUrl(document.folderId)}">Vai alla cartella ${icon("arrow-right")}</a><a class="primary" href="/api/conversions/${encodeURIComponent(document.id)}/pdf">${icon("download")}Scarica PDF</a></div>`;
}
function initConvert() {
  destinationOptions($("upload-folder"), folderId);
  function selected() {
    const file = $("file").files[0];
    $("file-name").textContent = file
      ? file.name + " · " + bytes(file.size)
      : "DOCX · PPTX · PDF";
    if (file)
      $("upload-name").value = file.name.replace(/\.(docx|pptx|pdf)$/i, "");
    $("convert").innerHTML =
      icon(file?.name.toLowerCase().endsWith(".pdf") ? "upload" : "convert") +
      (file?.name.toLowerCase().endsWith(".pdf")
        ? "Salva PDF nell’archivio"
        : "Converti e salva in PDF");
  }
  $("file").onchange = selected;
  bindDrop($("drop"), (files) => {
    const dt = new DataTransfer();
    dt.items.add(files[0]);
    $("file").files = dt.files;
    selected();
  });
  $("upload-form").onsubmit = async (e) => {
    e.preventDefault();
    const file = $("file").files[0];
    if (!file || !/\.(docx|pptx|pdf)$/i.test(file.name)) {
      notice("Scegli un file DOCX, PPTX o PDF.", true);
      return;
    }
    const form = new FormData();
    form.append("file", file);
    if ($("upload-name").value.trim())
      form.append("displayName", $("upload-name").value);
    if ($("upload-folder").value)
      form.append("folderId", $("upload-folder").value);
    $("convert").disabled = true;
    $("convert").textContent = "Preparazione del documento…";
    notice("Sto preparando il tuo documento. Attendi il completamento.");
    try {
      const saved = await api(
        file.name.toLowerCase().endsWith(".pdf")
          ? "/api/documents"
          : "/api/conversions",
        { method: "POST", body: form },
      );
      documents.unshift(saved);
      renderNavigation();
      result($("conversion-result"), saved, "Documento pronto");
      notice("Documento salvato. Lo ritrovi nel tuo archivio.");
      download(saved.id);
    } catch (e) {
      report(e);
    } finally {
      $("convert").disabled = false;
      selected();
    }
  };
}
const sourceTitle = (item) =>
  item.file?.name || item.document.displayName || item.document.name;
function renderQueue() {
  const count = queue.length;
  $("queue-count").textContent = count;
  $("merge-total").textContent = count;
  $("merge-size").textContent = bytes(
    queue.reduce(
      (sum, item) => sum + (item.file?.size || item.document.pdfSize),
      0,
    ),
  );
  $("queue-empty").hidden = count > 0;
  $("merge-submit").disabled = count < 2;
  $("merge-queue").innerHTML = queue
    .map(
      (item, i) =>
        `<li class="queue-item" draggable="true" data-key="${item.key}"><span class="drag-handle" aria-hidden="true">⠿</span><span class="queue-order">${String(i + 1).padStart(2, "0")}</span><span class="document-icon pdf">${icon("file")}</span><div class="queue-file"><strong>${esc(sourceTitle(item))}</strong><span>${item.file ? "Dal dispositivo" : "Archivio · " + esc(folderPath(item.document.folderId))} · ${bytes(item.file?.size || item.document.pdfSize)}</span></div><div class="queue-controls"><button class="icon-button" data-up="${item.key}" aria-label="Sposta ${esc(sourceTitle(item))} prima" ${i === 0 ? "disabled" : ""}>${icon("up")}</button><button class="icon-button" data-down="${item.key}" aria-label="Sposta ${esc(sourceTitle(item))} dopo" ${i === queue.length - 1 ? "disabled" : ""}>${icon("down")}</button><button class="icon-button" data-remove="${item.key}" aria-label="Rimuovi ${esc(sourceTitle(item))}">${icon("close")}</button></div></li>`,
    )
    .join("");
  function move(key, offset) {
    const i = queue.findIndex((x) => x.key === key);
    if (i + offset >= 0 && i + offset < queue.length) {
      [queue[i], queue[i + offset]] = [queue[i + offset], queue[i]];
      renderQueue();
    }
  }
  $("merge-queue")
    .querySelectorAll("[data-up]")
    .forEach((b) => (b.onclick = () => move(b.dataset.up, -1)));
  $("merge-queue")
    .querySelectorAll("[data-down]")
    .forEach((b) => (b.onclick = () => move(b.dataset.down, 1)));
  $("merge-queue")
    .querySelectorAll("[data-remove]")
    .forEach(
      (b) =>
        (b.onclick = () => {
          queue = queue.filter((x) => x.key !== b.dataset.remove);
          renderQueue();
        }),
    );
  for (const row of $("merge-queue").children) {
    row.addEventListener("dragstart", (e) => {
      dragged = row.dataset.key;
      e.dataTransfer.setData("text/plain", dragged);
      row.classList.add("dragging");
    });
    row.addEventListener("dragend", () => {
      dragged = null;
      row.classList.remove("dragging");
    });
    row.addEventListener("dragover", (e) => {
      e.preventDefault();
      row.classList.add("drop-target");
    });
    row.addEventListener("dragleave", () =>
      row.classList.remove("drop-target"),
    );
    row.addEventListener("drop", (e) => {
      e.preventDefault();
      const target = queue.findIndex((x) => x.key === row.dataset.key),
        source = queue.findIndex((x) => x.key === dragged);
      if (source >= 0 && target >= 0 && source !== target) {
        queue.splice(target, 0, queue.splice(source, 1)[0]);
        renderQueue();
      }
      row.classList.remove("drop-target");
    });
  }
}
function addUploads(files) {
  const invalid = files.filter((f) => !f.name.toLowerCase().endsWith(".pdf"));
  if (invalid.length) notice("Sono ammessi soltanto file PDF.", true);
  for (const file of files.filter((f) => f.name.toLowerCase().endsWith(".pdf")))
    queue.push({ key: crypto.randomUUID(), file });
  renderQueue();
}
function renderLibrary() {
  const search = $("library-search").value.toLocaleLowerCase("it");
  $("library-list").innerHTML =
    documents
      .filter((d) =>
        `${d.displayName} ${d.name}`.toLocaleLowerCase("it").includes(search),
      )
      .map(
        (d) =>
          `<label class="library-item"><input type="checkbox" value="${esc(d.id)}">${icon("file")}<div><strong>${esc(d.displayName || d.name)}</strong><small>${esc(folderPath(d.folderId))} · ${bytes(d.pdfSize)}${d.pages ? " · " + d.pages + " pagine" : ""}</small></div><span class="count-badge">PDF</span></label>`,
      )
      .join("") ||
    '<p class="library-empty">Nessun documento disponibile. Carica o converti un file per ritrovarlo qui.</p>';
}
function initMerge() {
  destinationOptions($("merge-folder"), folderId);
  const preselected = documents.find((d) => d.id === params.get("file"));
  if (preselected)
    queue.push({ key: crypto.randomUUID(), document: preselected });
  renderQueue();
  $("merge-files").onchange = () => {
    addUploads([...$("merge-files").files]);
    $("merge-files").value = "";
  };
  bindDrop($("merge-drop"), addUploads);
  $("choose-stored").onclick = () => {
    $("library-search").value = "";
    renderLibrary();
    modal($("library-dialog"));
  };
  $("library-search").oninput = renderLibrary;
  $("add-stored").onclick = () => {
    for (const input of $("library-list").querySelectorAll("input:checked")) {
      const document = documents.find((d) => d.id === input.value);
      queue.push({ key: crypto.randomUUID(), document });
    }
    $("library-dialog").close();
    renderQueue();
  };
  $("merge-form").onsubmit = async (e) => {
    e.preventDefault();
    if (queue.length < 2) return;
    const form = new FormData(),
      items = [];
    let index = 0;
    for (const item of queue) {
      if (item.file) {
        items.push({ type: "upload", index: index++ });
        form.append("files", item.file);
      } else items.push({ type: "stored", id: item.document.id });
    }
    const request = {
      name: $("merge-name").value,
      folderId: $("merge-folder").value || null,
      items,
    };
    form.append(
      "request",
      new Blob([JSON.stringify(request)], { type: "application/json" }),
    );
    $("merge-submit").disabled = true;
    $("merge-submit").textContent = "Unione dei documenti…";
    notice("Sto unendo i PDF nell’ordine scelto.");
    try {
      const saved = await api("/api/merges", { method: "POST", body: form });
      documents.unshift(saved);
      renderNavigation();
      result($("merge-result"), saved, "Il tuo PDF è pronto");
      notice("PDF unito e salvato nel tuo archivio.");
      download(saved.id);
    } catch (e) {
      report(e);
    } finally {
      $("merge-submit").disabled = queue.length < 2;
      $("merge-submit").innerHTML = icon("merge") + "Unisci e salva PDF";
    }
  };
}
async function loginPage() {
  icons();
  if (params.has("auth_error"))
    notice("Accesso OIDC non riuscito. Riprova o verifica il provider.", true);
  const config = await api("/api/auth/config");
  $("login-form").hidden = config.mode === "oidc";
  $("oidc-login").hidden = config.mode !== "oidc";
  $("switch-mode").hidden = !config.registration;
  let register = false;
  const next = params.get("next");
  const nextPath =
    next && /^\/(archivio|converti|unisci)(\?|$)/.test(next)
      ? next
      : "/archivio";
  $("switch-mode").onclick = () => {
    register = !register;
    $("login-title").textContent = register
      ? "Crea il tuo spazio."
      : "Il tuo spazio ti aspetta.";
    $("login-description").textContent = register
      ? "Un account, una casa per i tuoi documenti."
      : "Accedi per ritrovare i tuoi documenti.";
    $("submit-login").innerHTML =
      (register ? "Crea account" : "Accedi al tuo spazio") +
      " " +
      icon("arrow-right");
    $("switch-mode").textContent = register
      ? "Hai già un account? Accedi"
      : "Crea un account";
    $("login-password").autocomplete = register
      ? "new-password"
      : "current-password";
    $("login-password").minLength = register ? 12 : 1;
  };
  $("login-form").onsubmit = async (e) => {
    e.preventDefault();
    $("submit-login").disabled = true;
    try {
      const credentials = {
        username: $("login-user").value,
        password: $("login-password").value,
      };
      if (register)
        await api("/api/auth/register", jsonRequest("POST", credentials));
      await api("/api/auth/login", jsonRequest("POST", credentials));
      location.assign(nextPath);
    } catch (e) {
      report(e);
    } finally {
      $("submit-login").disabled = false;
    }
  };
}
(async () => {
  try {
    if (page === "login") {
      await loginPage();
      document.body.dataset.ready = "true";
      return;
    }
    user = await api("/api/auth/me");
    shell();
    commonDialogs();
    await reloadData();
    if (folderId && !folders.some((f) => f.id === folderId))
      throw Error(
        "Cartella non trovata. Torna all’archivio per scegliere una posizione valida.",
      );
    if (page === "archive") {
      renderArchive();
      $("new-folder").onclick = () => editFolder();
      $("search").oninput = renderDocuments;
      $("kind-filter").onchange = renderDocuments;
      $("refresh").onclick = () =>
        reloadData()
          .then(renderArchive)
          .catch((e) => report(e));
    }
    if (page === "convert") initConvert();
    if (page === "merge") initMerge();
    document.body.dataset.ready = "true";
  } catch (e) {
    report(e);
  }
})();
