(function () {
  "use strict";

  if (window.top !== window || document.querySelector("#fo-notes-filler")) return;

  const parser = globalThis.FoNotesParser;
  const zipApi = globalThis.FoNotesZip;
  let parsedExport = null;
  let zipEntries = null;
  let running = false;
  let cancelRequested = false;

  const launcher = document.createElement("button");
  launcher.id = "fo-notes-launcher";
  launcher.type = "button";
  launcher.textContent = "FO";
  launcher.title = "Ouvrir l’assistant Fo Notes";

  const overlay = document.createElement("section");
  overlay.id = "fo-notes-filler";
  overlay.setAttribute("aria-label", "Assistant Fo Notes");
  overlay.innerHTML = `
    <div class="fo-panel">
      <header class="fo-header">
        <span class="fo-logo">FO</span>
        <div class="fo-title">
          <strong>Remplissage ATOS</strong>
          <span>Fo Notes prépare la saisie, sans envoyer la note de frais.</span>
        </div>
        <button class="fo-icon-button" id="fo-close" type="button" aria-label="Fermer">×</button>
      </header>
      <div class="fo-body">
        <div class="fo-field">
          <label for="fo-email">Mail récapitulatif Fo Notes</label>
          <textarea id="fo-email" placeholder="Collez ici le contenu complet du mail…"></textarea>
        </div>
        <div class="fo-field">
          <label for="fo-zip">Archive ZIP Fo Notes (facultative)</label>
          <input id="fo-zip" type="file" accept=".zip,application/zip">
          <p class="fo-help">Avec le ZIP, le CSV devient la référence et les PDF sont ajoutés à ATOS.</p>
        </div>
        <div class="fo-actions">
          <button id="fo-analyse" class="fo-primary" type="button">Analyser l’export</button>
          <button id="fo-reset" class="fo-secondary" type="button">Réinitialiser</button>
        </div>
        <section id="fo-summary" class="fo-summary">
          <div id="fo-meta" class="fo-meta"></div>
          <div class="fo-table-wrap">
            <table>
              <thead>
                <tr><th>Date</th><th>Type</th><th>Remboursable</th><th>Description ATOS</th><th>PDF</th></tr>
              </thead>
              <tbody id="fo-lines"></tbody>
            </table>
          </div>
          <label class="fo-confirm">
            <input id="fo-confirm" type="checkbox">
            <span>Je confirme être sur <b>Enter Receipts</b> et que ces lignes ne sont pas déjà présentes.</span>
          </label>
          <div class="fo-actions">
            <button id="fo-fill" class="fo-primary" type="button" disabled>Remplir ATOS</button>
            <button id="fo-cancel" class="fo-danger" type="button" hidden>Arrêter</button>
          </div>
        </section>
        <pre id="fo-log" class="fo-log" aria-live="polite">Prêt.</pre>
      </div>
    </div>`;

  document.documentElement.append(launcher, overlay);

  const elements = {
    email: overlay.querySelector("#fo-email"),
    zip: overlay.querySelector("#fo-zip"),
    analyse: overlay.querySelector("#fo-analyse"),
    reset: overlay.querySelector("#fo-reset"),
    summary: overlay.querySelector("#fo-summary"),
    meta: overlay.querySelector("#fo-meta"),
    lines: overlay.querySelector("#fo-lines"),
    confirm: overlay.querySelector("#fo-confirm"),
    fill: overlay.querySelector("#fo-fill"),
    cancel: overlay.querySelector("#fo-cancel"),
    log: overlay.querySelector("#fo-log"),
    close: overlay.querySelector("#fo-close")
  };

  function open() {
    overlay.classList.add("fo-open");
    launcher.hidden = true;
  }

  function close() {
    if (running) return;
    overlay.classList.remove("fo-open");
    launcher.hidden = false;
  }

  function log(message, kind = "") {
    const timestamp = new Date().toLocaleTimeString("fr-FR");
    elements.log.textContent += `\n[${timestamp}] ${message}`;
    elements.log.className = `fo-log${kind ? ` fo-${kind}` : ""}`;
    elements.log.scrollTop = elements.log.scrollHeight;
  }

  function reset() {
    if (running) return;
    parsedExport = null;
    zipEntries = null;
    elements.email.value = "";
    elements.zip.value = "";
    elements.confirm.checked = false;
    elements.fill.disabled = true;
    elements.summary.classList.remove("fo-visible");
    elements.lines.replaceChildren();
    elements.log.textContent = "Prêt.";
    elements.log.className = "fo-log";
  }

  function basename(name) {
    return String(name).split("/").at(-1);
  }

  function attachmentFilesForExport() {
    if (!zipEntries || !parsedExport) return [];
    const requested = [...new Set(
      parsedExport.entries.flatMap((entry) => entry.attachments || [])
    )];
    if (requested.length) {
      return requested.map((name) => {
        const found = zipApi.findEntry(zipEntries, name);
        if (!found) throw new Error(`Pièce jointe absente du ZIP : ${name}`);
        return found;
      });
    }
    return [...zipEntries]
      .filter(([name]) => /\.(pdf|jpe?g|png)$/i.test(name))
      .map(([name, bytes]) => ({ name, bytes }));
  }

  function renderPreview() {
    const files = attachmentFilesForExport();
    elements.meta.textContent =
      `${parsedExport.tripName || "Déplacement"} · ` +
      `${parsedExport.startDate} au ${parsedExport.endDate} · ` +
      `${parsedExport.entries.length} ligne(s) · ${files.length} justificatif(s)`;
    elements.lines.replaceChildren();
    for (const entry of parsedExport.entries) {
      const row = document.createElement("tr");
      for (const value of [
        entry.date,
        entry.label,
        `${parser.money(entry.reimbursableAmount)} €`,
        entry.description,
        entry.attachments.length ? entry.attachments.map(basename).join(", ") : "—"
      ]) {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      }
      elements.lines.append(row);
    }
    elements.summary.classList.add("fo-visible");
    elements.fill.disabled = !elements.confirm.checked;
  }

  async function analyse() {
    if (running) return;
    elements.analyse.disabled = true;
    elements.log.textContent = "Analyse en cours…";
    elements.log.className = "fo-log";
    try {
      zipEntries = null;
      let csvText = "";
      const zipFile = elements.zip.files[0];
      if (zipFile) {
        zipEntries = await zipApi.readZip(zipFile);
        const csv = zipApi.findCsvEntry(zipEntries);
        if (!csv) {
          throw new Error("Aucun fichier CSV n’a été trouvé dans le ZIP.");
        }
        csvText = new TextDecoder("utf-8").decode(csv.bytes);
      }
      parsedExport = parser.parseExport(elements.email.value, csvText);
      renderPreview();
      elements.confirm.checked = false;
      elements.fill.disabled = true;
      elements.log.textContent =
        `Analyse terminée : ${parsedExport.entries.length} ligne(s) prête(s).`;
      elements.log.className = "fo-log fo-success";
    } catch (error) {
      parsedExport = null;
      elements.summary.classList.remove("fo-visible");
      elements.log.textContent = error.message || String(error);
      elements.log.className = "fo-log fo-error";
    } finally {
      elements.analyse.disabled = false;
    }
  }

  function normalizeText(value) {
    return parser.comparable(value).replace(/\s+/g, " ");
  }

  function isVisible(node) {
    if (!node?.isConnected) return false;
    const style = node.ownerDocument.defaultView.getComputedStyle(node);
    const rect = node.getBoundingClientRect();
    return style.display !== "none" &&
      style.visibility !== "hidden" &&
      rect.width > 0 &&
      rect.height > 0;
  }

  function documents(rootDocument = document) {
    const result = [rootDocument];
    for (const frame of rootDocument.querySelectorAll("iframe")) {
      try {
        if (frame.contentDocument) result.push(...documents(frame.contentDocument));
      } catch {
        // Les cadres d’une autre origine sont volontairement ignorés.
      }
    }
    return result;
  }

  function findAtosDocument() {
    return documents().find((doc) =>
      [...doc.querySelectorAll("th")].some((node) =>
        normalizeText(node.textContent).includes("receipts in this expense report")
      )
    ) || null;
  }

  function findButton(doc, expected) {
    const target = normalizeText(expected);
    return [...doc.querySelectorAll("[role='button'], button")]
      .filter(isVisible)
      .find((node) => {
        const text = normalizeText(node.innerText || node.textContent);
        return text === target ||
          (target === "accept" && text.startsWith("accept ") && !text.includes("new entry"));
      }) || null;
  }

  function findLabelField(doc, labelText) {
    const target = normalizeText(labelText);
    const label = [...doc.querySelectorAll("label")]
      .find((node) => normalizeText(node.textContent) === target);
    if (!label) return null;
    const id = label.getAttribute("for");
    const field = id ? doc.getElementById(id) : null;
    return isVisible(field) ? field : null;
  }

  function findHeader(doc, title) {
    const target = normalizeText(title);
    return [...doc.querySelectorAll("th")]
      .filter(isVisible)
      .find((node) => normalizeText(node.textContent) === target) || null;
  }

  function findColumnControl(doc, title) {
    const header = findHeader(doc, title);
    if (!header) return null;
    const headerRect = header.getBoundingClientRect();
    const controls = [...doc.querySelectorAll(
      "input:not([type='hidden']), textarea, [role='textbox']"
    )].filter((node) => {
      if (!isVisible(node) || node.closest("th")) return false;
      const rect = node.getBoundingClientRect();
      const center = rect.left + rect.width / 2;
      return center >= headerRect.left - 3 &&
        center <= headerRect.right + 3 &&
        rect.top >= headerRect.bottom - 2 &&
        rect.top <= headerRect.bottom + 160;
    });
    return controls.sort(
      (left, right) => left.getBoundingClientRect().top - right.getBoundingClientRect().top
    )[0] || null;
  }

  function sleep(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
  }

  async function waitFor(factory, message, timeout = 12000) {
    const started = Date.now();
    while (Date.now() - started < timeout) {
      if (cancelRequested) throw new Error("Opération arrêtée par l’utilisateur.");
      const value = factory();
      if (value) return value;
      await sleep(150);
    }
    throw new Error(message);
  }

  async function settle(doc, minimum = 700) {
    await sleep(minimum);
    await waitFor(
      () => ![...doc.querySelectorAll("[class*='Busy'], [aria-busy='true']")].some(isVisible),
      "ATOS est resté occupé trop longtemps.",
      15000
    );
  }

  function setFieldValue(field, value) {
    const view = field.ownerDocument.defaultView;
    field.focus();
    const prototype = field.tagName === "TEXTAREA"
      ? view.HTMLTextAreaElement.prototype
      : view.HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (setter) setter.call(field, value);
    else field.value = value;
    field.dispatchEvent(new view.InputEvent("input", {
      bubbles: true,
      inputType: "insertText",
      data: value
    }));
    field.dispatchEvent(new view.Event("change", { bubbles: true }));
    field.dispatchEvent(new view.KeyboardEvent("keyup", { bubbles: true, key: "Tab" }));
    field.blur();
  }

  function atosDate(isoDate) {
    const [year, month, day] = isoDate.split("-");
    return `${day}.${month}.${year}`;
  }

  async function selectExpenseType(doc, label) {
    const control = await waitFor(
      () => findColumnControl(doc, "Expense Type"),
      "Champ « Expense Type » introuvable."
    );
    control.click();
    await sleep(250);
    const target = normalizeText(label);
    const option = await waitFor(() => {
      const candidates = [...doc.querySelectorAll("[role='option']")];
      const exact = candidates.find((node) =>
        normalizeText(node.getAttribute("aria-label") || node.textContent) === target
      );
      if (exact) exact.scrollIntoView({ block: "center" });
      return exact && isVisible(exact) ? exact : null;
    }, `Type de dépense ATOS introuvable : ${label}`);
    option.click();
    await settle(doc, 500);
  }

  async function fillEntry(doc, entry, index, total) {
    log(`Ligne ${index + 1}/${total} : ${entry.label}`);
    await selectExpenseType(doc, entry.label);

    const amount = await waitFor(
      () => findColumnControl(doc, "Receipt Amount"),
      "Champ « Receipt Amount » introuvable."
    );
    setFieldValue(amount, parser.money(entry.reimbursableAmount));

    const receiptDate = await waitFor(
      () => findColumnControl(doc, "Receipt Date"),
      "Champ « Receipt Date » introuvable."
    );
    setFieldValue(receiptDate, atosDate(entry.date));

    const fromDate = await waitFor(
      () => findLabelField(doc, "From Date"),
      "Champ « From Date » introuvable."
    );
    setFieldValue(fromDate, atosDate(entry.date));

    const toDate = await waitFor(
      () => findLabelField(doc, "To Date"),
      "Champ « To Date » introuvable."
    );
    setFieldValue(toDate, atosDate(entry.date));

    const description = await waitFor(
      () => findLabelField(doc, "Description"),
      "Champ « Description » introuvable."
    );
    setFieldValue(description, entry.description);
    await sleep(300);

    const action = index === total - 1 ? "Accept" : "Accept and New Entry";
    const button = await waitFor(
      () => findButton(doc, action),
      `Bouton « ${action} » introuvable.`
    );
    button.click();
    await settle(doc, 900);
  }

  function mimeFor(name) {
    if (/\.pdf$/i.test(name)) return "application/pdf";
    if (/\.png$/i.test(name)) return "image/png";
    return "image/jpeg";
  }

  async function uploadAttachments(doc, files) {
    if (!files.length) return;
    log(`Ouverture de la fenêtre des pièces jointes (${files.length}).`);
    const attachButton = await waitFor(
      () => findButton(doc, "Attach Receipts"),
      "Bouton « Attach Receipts » introuvable."
    );
    attachButton.click();

    for (let index = 0; index < files.length; index += 1) {
      const item = files[index];
      const input = await waitFor(
        () => [...doc.querySelectorAll("input[type='file']")].find(isVisible),
        "Champ de sélection d’une pièce jointe introuvable."
      );
      const file = new File([item.bytes], basename(item.name), {
        type: mimeFor(item.name)
      });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event("change", { bubbles: true }));
      log(`Pièce ${index + 1}/${files.length} : ${file.name}`);

      const uploadButton = await waitFor(
        () => findButton(doc, "Upload"),
        "Bouton « Upload » introuvable."
      );
      uploadButton.click();
      await settle(doc, 1100);
    }

    const closeButton = findButton(doc, "Close");
    if (closeButton) {
      closeButton.click();
      await settle(doc, 500);
    }
  }

  async function fillAtos() {
    if (!parsedExport || running || !elements.confirm.checked) return;
    running = true;
    cancelRequested = false;
    elements.fill.disabled = true;
    elements.analyse.disabled = true;
    elements.reset.disabled = true;
    elements.close.disabled = true;
    elements.cancel.hidden = false;
    elements.log.textContent = "Démarrage du remplissage…";
    elements.log.className = "fo-log";

    try {
      const doc = findAtosDocument();
      if (!doc) {
        throw new Error("Écran « Enter Receipts » introuvable dans la page actuelle.");
      }
      const newEntry = findButton(doc, "New Entry");
      if (!newEntry) throw new Error("Bouton « New Entry » introuvable.");
      const files = attachmentFilesForExport();

      newEntry.click();
      await settle(doc, 800);
      for (let index = 0; index < parsedExport.entries.length; index += 1) {
        await fillEntry(doc, parsedExport.entries[index], index, parsedExport.entries.length);
      }
      await uploadAttachments(doc, files);
      log(
        "Remplissage terminé. Vérifiez les lignes et les pièces jointes avant Review/Send.",
        "success"
      );
    } catch (error) {
      log(error.message || String(error), "error");
    } finally {
      running = false;
      elements.analyse.disabled = false;
      elements.reset.disabled = false;
      elements.close.disabled = false;
      elements.cancel.hidden = true;
      elements.fill.disabled = !elements.confirm.checked;
    }
  }

  launcher.addEventListener("click", open);
  elements.close.addEventListener("click", close);
  elements.analyse.addEventListener("click", analyse);
  elements.reset.addEventListener("click", reset);
  elements.confirm.addEventListener("change", () => {
    elements.fill.disabled = !elements.confirm.checked || !parsedExport || running;
  });
  elements.fill.addEventListener("click", fillAtos);
  elements.cancel.addEventListener("click", () => {
    cancelRequested = true;
    log("Arrêt demandé ; l’opération s’interrompra après l’action en cours.");
  });
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay) close();
  });

  chrome.runtime.onMessage.addListener((message) => {
    if (message?.type === "FO_NOTES_OPEN_FILLER") open();
  });
})();
