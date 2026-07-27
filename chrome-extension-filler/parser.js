(function (root, factory) {
  const api = factory();
  root.FoNotesParser = api;
  if (typeof module === "object" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MONTHS = {
    janvier: 1,
    fevrier: 2,
    mars: 3,
    avril: 4,
    mai: 5,
    juin: 6,
    juillet: 7,
    aout: 8,
    septembre: 9,
    octobre: 10,
    novembre: 11,
    decembre: 12
  };

  function clean(value) {
    return String(value ?? "")
      .replace(/\u00a0/g, " ")
      .replace(/\r/g, "")
      .trim();
  }

  function comparable(value) {
    return clean(value)
      .normalize("NFD")
      .replace(/\p{M}/gu, "")
      .toLowerCase()
      .replace(/\s+/g, " ");
  }

  function parseMoney(value) {
    const normalized = clean(value)
      .replace(/\s/g, "")
      .replace(",", ".")
      .replace(/[^\d.-]/g, "");
    const result = Number(normalized);
    if (!Number.isFinite(result)) throw new Error(`Montant invalide : ${value}`);
    return Math.round((result + Number.EPSILON) * 100) / 100;
  }

  function money(value) {
    return Number(value).toFixed(2).replace(".", ",");
  }

  function parseFrenchDateHeader(line) {
    const match = comparable(line).match(
      /^(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\s+(\d{1,2})\s+([a-z]+)\s+(\d{4})$/
    );
    if (!match) return null;
    const month = MONTHS[match[2]];
    if (!month) return null;
    return `${match[3]}-${String(month).padStart(2, "0")}-${match[1].padStart(2, "0")}`;
  }

  function fallbackDescription(label, date) {
    return `${label} — ${date}`;
  }

  function mergeCumulated(entries) {
    const merged = [];
    const positions = new Map();
    for (const entry of entries) {
      const isCumulated = /lnch\s*\+\s*dnnr.*cumulated/i.test(entry.label);
      const key = isCumulated ? `${entry.date}|${comparable(entry.label)}` : null;
      if (!key || !positions.has(key)) {
        positions.set(key, merged.length);
        merged.push({ ...entry, attachments: [...(entry.attachments || [])] });
        continue;
      }
      const target = merged[positions.get(key)];
      target.amount = Math.round((target.amount + entry.amount) * 100) / 100;
      target.reimbursableAmount = Math.round(
        (target.reimbursableAmount + entry.reimbursableAmount) * 100
      ) / 100;
      target.attachments.push(...(entry.attachments || []));
      const descriptions = [target.description, entry.description]
        .map(clean)
        .filter(Boolean);
      target.description = [...new Set(descriptions)].join(" / ");
    }
    return merged;
  }

  function parseEmail(text) {
    const lines = String(text ?? "").replace(/\r/g, "").split("\n");
    const header = lines.join("\n");
    const tripMatch = header.match(/déplacement\s+[«"]([^»"]+)[»"]/i);
    const periodMatch = header.match(
      /Période\s*:\s*du\s*(\d{4}-\d{2}-\d{2})\s*au\s*(\d{4}-\d{2}-\d{2})/i
    );
    let currentDate = null;
    const entries = [];

    for (const rawLine of lines) {
      const line = clean(rawLine);
      const date = parseFrenchDateHeader(line);
      if (date) {
        currentDate = date;
        continue;
      }
      if (!/^[•▪●*-]\s*/.test(line)) continue;
      if (!currentDate) throw new Error(`Date absente avant la ligne : ${line}`);

      const match = line.match(
        /^[•▪●*-]\s*(\d{2})\s+(.+?)\s+[—–-]\s+([\d\s]+(?:[,.]\d{1,2})?)\s*€(.*)$/i
      );
      if (!match) throw new Error(`Ligne de dépense non reconnue : ${line}`);

      const label = `${match[1]} ${clean(match[2])}`;
      const amount = parseMoney(match[3]);
      const tail = match[4] || "";
      const reimbursableMatch = tail.match(
        /\(\s*remboursable\s*:\s*([\d\s]+(?:[,.]\d{1,2})?)\s*€\s*\)/i
      );
      const descriptionMatch = tail.match(
        /[—–-]\s*(?:commentaire|description)\s*:\s*(.+)$/i
      );
      entries.push({
        date: currentDate,
        code: match[1],
        label,
        amount,
        reimbursableAmount: reimbursableMatch
          ? parseMoney(reimbursableMatch[1])
          : amount,
        description: clean(descriptionMatch?.[1]) ||
          fallbackDescription(label, currentDate),
        attachments: []
      });
    }

    if (!entries.length) {
      throw new Error("Aucune ligne de dépense n’a été trouvée dans le mail.");
    }

    return {
      source: "mail",
      tripName: clean(tripMatch?.[1]),
      startDate: periodMatch?.[1] || entries[0].date,
      endDate: periodMatch?.[2] || entries.at(-1).date,
      entries: mergeCumulated(entries)
    };
  }

  function parseCsvRows(text) {
    const rows = [];
    let row = [];
    let cell = "";
    let quoted = false;
    const source = String(text ?? "").replace(/^\uFEFF/, "");

    for (let index = 0; index < source.length; index += 1) {
      const char = source[index];
      if (quoted) {
        if (char === '"' && source[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else if (char === '"') {
          quoted = false;
        } else {
          cell += char;
        }
      } else if (char === '"') {
        quoted = true;
      } else if (char === ";") {
        row.push(cell);
        cell = "";
      } else if (char === "\n") {
        row.push(cell.replace(/\r$/, ""));
        rows.push(row);
        row = [];
        cell = "";
      } else {
        cell += char;
      }
    }
    if (cell || row.length) {
      row.push(cell);
      rows.push(row);
    }
    return rows;
  }

  function parseCsv(text) {
    const rows = parseCsvRows(text);
    const headerIndex = rows.findIndex((row) =>
      comparable(row[0]) === "date" &&
      row.some((cell) => comparable(cell) === "libelle")
    );
    if (headerIndex < 0) {
      throw new Error("L’en-tête des dépenses est introuvable dans recapitulatif.csv.");
    }

    const metadata = new Map();
    for (const row of rows.slice(0, headerIndex)) {
      if (clean(row[0])) metadata.set(comparable(row[0]), clean(row[1]));
    }

    const headers = rows[headerIndex].map(comparable);
    const column = (name) => headers.indexOf(comparable(name));
    const dateColumn = column("Date");
    const codeColumn = column("Code");
    const labelColumn = column("Libellé");
    const amountColumn = column("Montant TTC");
    const reimbursableColumn = column("Montant remboursable");
    const descriptionColumn = headers.findIndex((header) =>
      header.includes("commentaire") || header.includes("description")
    );
    const attachmentColumn = headers.findIndex((header) =>
      header.includes("piece jointe")
    );

    const entries = [];
    for (const row of rows.slice(headerIndex + 1)) {
      const date = clean(row[dateColumn]);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue;
      const code = clean(row[codeColumn]);
      const rawLabel = clean(row[labelColumn]);
      const label = rawLabel.startsWith(`${code} `)
        ? rawLabel
        : `${code} ${rawLabel}`;
      const amount = parseMoney(row[amountColumn]);
      const reimbursable = reimbursableColumn >= 0 && clean(row[reimbursableColumn])
        ? parseMoney(row[reimbursableColumn])
        : amount;
      const description = descriptionColumn >= 0
        ? clean(row[descriptionColumn])
        : "";
      entries.push({
        date,
        code,
        label,
        amount,
        reimbursableAmount: reimbursable,
        description: description || fallbackDescription(label, date),
        attachments: attachmentColumn >= 0
          ? clean(row[attachmentColumn]).split(/\s*\|\s*/).filter(Boolean)
          : []
      });
    }

    if (!entries.length) {
      throw new Error("Aucune dépense n’a été trouvée dans recapitulatif.csv.");
    }

    return {
      source: "csv",
      tripName: metadata.get("deplacement") || "",
      startDate: metadata.get("debut") || entries[0].date,
      endDate: metadata.get("fin") || entries.at(-1).date,
      entries: mergeCumulated(entries)
    };
  }

  function parseExport(emailText, csvText) {
    return clean(csvText) ? parseCsv(csvText) : parseEmail(emailText);
  }

  return {
    comparable,
    fallbackDescription,
    mergeCumulated,
    money,
    parseCsv,
    parseCsvRows,
    parseEmail,
    parseExport,
    parseMoney
  };
});
