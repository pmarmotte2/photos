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
          <span>Fo Notes v${chrome.runtime.getManifest().version} prépare la saisie, sans envoyer la note de frais.</span>
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
          <p class="fo-help">Avec le ZIP, le CSV devient la référence pour la saisie des lignes.</p>
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
                <tr><th>Date</th><th>Type</th><th>Remboursable</th><th>Description ATOS</th></tr>
              </thead>
              <tbody id="fo-lines"></tbody>
            </table>
          </div>
          <label class="fo-confirm">
            <input id="fo-confirm" type="checkbox">
            <span>Je confirme être sur <b>Enter Receipts</b> et que ces lignes ne sont pas déjà présentes.</span>
          </label>
          <label class="fo-confirm">
            <input id="fo-cautious" type="checkbox" checked>
            <span><b>Mode prudent</b> : pause de 0,5 seconde entre les actions.</span>
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
    cautious: overlay.querySelector("#fo-cautious"),
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

  function renderPreview() {
    elements.meta.textContent =
      `${parsedExport.tripName || "Déplacement"} · ` +
      `${parsedExport.startDate} au ${parsedExport.endDate} · ` +
      `${parsedExport.entries.length} ligne(s)`;
    elements.lines.replaceChildren();
    for (const entry of parsedExport.entries) {
      const row = document.createElement("tr");
      for (const value of [
        entry.date,
        entry.label,
        `${parser.money(entry.reimbursableAmount)} €`,
        entry.description
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

  function isDisabled(node) {
    return Boolean(
      node?.disabled ||
      node?.getAttribute("aria-disabled") === "true" ||
      /\bdisabled\b/i.test(node?.className || "")
    );
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

  function receiptTableColumn(doc, title) {
    const header = findHeader(doc, title);
    if (!header) return null;
    const row = header.closest("tr");
    const table = header.closest("table");
    if (!row || !table) return null;
    const cells = [...row.children].filter((node) =>
      node.tagName === "TH" || node.tagName === "TD"
    );
    const columnIndex = cells.indexOf(header);
    return columnIndex >= 0 ? { table, headerRow: row, columnIndex } : null;
  }

  function rowControl(row, columnIndex) {
    const cells = [...row.children].filter((node) =>
      node.tagName === "TH" || node.tagName === "TD"
    );
    const cell = cells[columnIndex];
    if (!cell) return null;
    return [...cell.querySelectorAll(
      "input:not([type='hidden']), textarea, [role='textbox']"
    )].find(isVisible) || null;
  }

  function receiptRows(doc) {
    const typeColumn = receiptTableColumn(doc, "Expense Type");
    if (!typeColumn) return [];
    return [...typeColumn.table.querySelectorAll("tr")]
      .filter((row) => row !== typeColumn.headerRow)
      .map((row) => ({
        row,
        typeControl: rowControl(row, typeColumn.columnIndex)
      }))
      .filter((candidate) => candidate.typeControl)
      .map((candidate, index) => ({ ...candidate, index }));
  }

  function findColumnControl(doc, title, rowIndex = null) {
    const column = receiptTableColumn(doc, title);
    if (column && Number.isInteger(rowIndex)) {
      const targetRow = receiptRows(doc)
        .find((candidate) => candidate.index === rowIndex)?.row;
      return targetRow ? rowControl(targetRow, column.columnIndex) : null;
    }

    // Repli géométrique pour une éventuelle variante du tableau ATOS.
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
    ).at(-1) || null;
  }

  function sleep(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
  }

  function pace(multiplier = 1) {
    const baseDelay = elements.cautious.checked ? 500 : 250;
    return sleep(Math.round(baseDelay * multiplier));
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

  function truncateForField(field, value, fallbackLimit) {
    const declaredLimit = Number.parseInt(
      field.getAttribute("maxlength") ||
      field.getAttribute("data-maxlength") ||
      "",
      10
    );
    const limit = Number.isInteger(declaredLimit) && declaredLimit > 0
      ? declaredLimit
      : fallbackLimit;
    const text = String(value);
    return {
      value: parser.truncateText(text, limit),
      limit,
      truncated: text.length > limit
    };
  }

  function atosDate(isoDate) {
    const [year, month, day] = isoDate.split("-");
    return `${day}.${month}.${year}`;
  }

  function semanticText(value) {
    return parser.semanticText(value);
  }

  function optionTexts(option) {
    const values = [
      option.getAttribute("aria-label"),
      option.getAttribute("title"),
      option.innerText,
      option.textContent
    ];
    const labelledBy = option.getAttribute("aria-labelledby");
    if (labelledBy) {
      for (const id of labelledBy.split(/\s+/)) {
        values.push(option.ownerDocument.getElementById(id)?.textContent);
      }
    }
    return [...new Set(values.map(semanticText).filter(Boolean))];
  }

  function optionMatchScore(option, target) {
    return optionTexts(option).reduce((best, candidate) => {
      return Math.max(best, parser.textMatchScore(candidate, target));
    }, 0);
  }

  function currentMenuOptions(control, atosDocument) {
    const availableDocuments = [...new Set([atosDocument, ...documents()])];
    const controlledIds = [
      control.getAttribute("aria-controls"),
      control.getAttribute("aria-owns")
    ]
      .filter(Boolean)
      .flatMap((value) => value.split(/\s+/))
      .filter(Boolean);

    const controlledContainers = controlledIds.flatMap((id) =>
      availableDocuments
        .map((doc) => doc.getElementById(id))
        .filter(Boolean)
    );
    const controlledOptions = controlledContainers.flatMap((container) =>
      container.matches("[role='option']")
        ? [container]
        : [...container.querySelectorAll("[role='option']")]
    );
    if (controlledOptions.length) return [...new Set(controlledOptions)];

    const openContainers = availableDocuments.flatMap((doc) =>
      [...doc.querySelectorAll("[role='listbox'], [role='menu']")].filter(isVisible)
    );
    const openOptions = openContainers.flatMap((container) =>
      [...container.querySelectorAll("[role='option']")]
    );
    if (openOptions.length) return [...new Set(openOptions)];

    const allOptions = availableDocuments.flatMap((doc) =>
      [...doc.querySelectorAll("[role='option']")]
    );
    const visibleOptions = allOptions.filter(isVisible);
    return visibleOptions.length ? visibleOptions : allOptions;
  }

  async function selectExpenseType(doc, label, rowIndex) {
    const control = await waitFor(
      () => findColumnControl(doc, "Expense Type", rowIndex),
      "Champ « Expense Type » introuvable."
    );
    control.click();
    await sleep(250);
    const candidates = await waitFor(
      () => {
        const options = currentMenuOptions(control, doc);
        return options.some(isVisible) ? options : null;
      },
      `Le menu des types de dépense ne s’est pas ouvert pour : ${label}`
    );
    const ranked = candidates
      .map((option) => ({
        option,
        score: optionMatchScore(option, label),
        visible: isVisible(option)
      }))
      .filter((candidate) => candidate.score > 0)
      .sort((left, right) =>
        Number(right.visible) - Number(left.visible) ||
        right.score - left.score
      );
    const option = ranked[0]?.option;
    if (!option) {
      const detected = candidates
        .filter(isVisible)
        .flatMap(optionTexts)
        .filter((value, index, values) => values.indexOf(value) === index)
        .slice(0, 8)
        .join(" | ");
      throw new Error(
        `Type de dépense ATOS introuvable : ${label}. ` +
        `Options détectées : ${detected || "aucune"}`
      );
    }
    option.scrollIntoView({ block: "center" });
    await sleep(150);
    const optionView = option.ownerDocument.defaultView;
    option.dispatchEvent(new optionView.MouseEvent("mousedown", {
      bubbles: true,
      cancelable: true,
      view: optionView
    }));
    option.dispatchEvent(new optionView.MouseEvent("mouseup", {
      bubbles: true,
      cancelable: true,
      view: optionView
    }));
    option.click();
    await settle(doc, elements.cautious.checked ? 1200 : 500);
  }

  async function fillEntry(doc, entry, index, total, rowIndex) {
    log(
      `Ligne ${index + 1}/${total} : ${entry.label} ` +
      `(ligne SAP ${rowIndex + 1})`
    );
    await selectExpenseType(doc, entry.label, rowIndex);
    await pace(0.6);

    const amount = await waitFor(
      () => findColumnControl(doc, "Receipt Amount", rowIndex),
      "Champ « Receipt Amount » introuvable."
    );
    setFieldValue(amount, parser.money(entry.reimbursableAmount));
    await pace();

    const receiptDate = await waitFor(
      () => findColumnControl(doc, "Receipt Date", rowIndex),
      "Champ « Receipt Date » introuvable."
    );
    setFieldValue(receiptDate, atosDate(entry.date));
    await pace(0.8);

    const fromDate = await waitFor(
      () => findLabelField(doc, "From Date"),
      "Champ « From Date » introuvable."
    );
    setFieldValue(fromDate, atosDate(entry.date));
    await pace(0.8);

    const toDate = await waitFor(
      () => findLabelField(doc, "To Date"),
      "Champ « To Date » introuvable."
    );
    setFieldValue(toDate, atosDate(entry.date));
    await pace(0.8);

    const description = await waitFor(
      () => findLabelField(doc, "Description"),
      "Champ « Description » introuvable."
    );
    const safeDescription = truncateForField(description, entry.description, 40);
    if (safeDescription.truncated) {
      log(
        `Description de la ligne ${index + 1} tronquée à ` +
        `${safeDescription.limit} caractères pour SAP.`
      );
    }
    setFieldValue(description, safeDescription.value);
    await pace();

    const action = index === total - 1 ? "Accept" : "Accept and New Entry";
    const button = await waitFor(
      () => {
        const candidate = findButton(doc, action);
        return candidate && !isDisabled(candidate) ? candidate : null;
      },
      `Bouton « ${action} » introuvable ou encore désactivé.`
    );
    const rowCountBeforeAccept = receiptRows(doc).length;
    await pace(0.7);
    dispatchFullClick(button);
    await settle(doc, elements.cautious.checked ? 1700 : 900);

    if (index < total - 1) {
      const nextRow = await waitFor(
        () => {
          const rows = receiptRows(doc);
          return rows.length > rowCountBeforeAccept
            ? rows.at(-1)
            : null;
        },
        "ATOS n’a pas créé une nouvelle ligne distincte.",
        20000
      );
      await pace(0.7);
      return nextRow.index;
    }
    return null;
  }

  function dispatchFullClick(element) {
    const view = element.ownerDocument.defaultView;
    element.dispatchEvent(new view.MouseEvent("mousedown", {
      bubbles: true,
      cancelable: true,
      view
    }));
    element.dispatchEvent(new view.MouseEvent("mouseup", {
      bubbles: true,
      cancelable: true,
      view
    }));
    element.click();
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

      log(
        elements.cautious.checked
          ? "Mode prudent actif : pause de 0,5 seconde entre les actions."
          : "Mode rapide actif."
      );
      const rowCountBeforeNewEntry = receiptRows(doc).length;
      dispatchFullClick(newEntry);
      await settle(doc, elements.cautious.checked ? 1500 : 800);
      const firstRow = await waitFor(
        () => {
          const rows = receiptRows(doc);
          return rows.length > rowCountBeforeNewEntry
            ? rows.at(-1)
            : null;
        },
        "ATOS n’a pas créé la première ligne de saisie."
      );
      let rowIndex = firstRow.index;
      for (let index = 0; index < parsedExport.entries.length; index += 1) {
        const nextRowIndex = await fillEntry(
          doc,
          parsedExport.entries[index],
          index,
          parsedExport.entries.length,
          rowIndex
        );
        if (nextRowIndex != null) rowIndex = nextRowIndex;
      }
      log(
        "Remplissage terminé. Vérifiez les lignes avant Review/Send.",
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
