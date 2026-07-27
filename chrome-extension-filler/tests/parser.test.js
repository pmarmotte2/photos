const test = require("node:test");
const assert = require("node:assert/strict");
const parser = require("../parser.js");

const sampleEmail = `Bonjour,

Veuillez trouver l’export Fo Notes du déplacement « cse les clayes ».
Période : du 2026-07-21 au 2026-07-23
Statut : Envoyée

Mardi 21 juillet 2026
• 01 Transports (occasional) — 5,10 €
• 02 Taxi — 20,00 €
• 06 Lnch+Dnnr Paris (cumulated) — 40,00 €

Mercredi 22 juillet 2026
• 06 Lnch+Dnnr Paris (cumulated) — 22,47 €
• 06 Lnch+Dnnr Paris (cumulated) — 22,35 €

Jeudi 23 juillet 2026
• 06 Dinner Paris — 20,48 € (remboursable : 14,48 €) — Commentaire : Dîner équipe
• 06 Lunch — 16,50 €
`;

test("le mail est converti en lignes ATOS", () => {
  const result = parser.parseEmail(sampleEmail);

  assert.equal(result.tripName, "cse les clayes");
  assert.equal(result.startDate, "2026-07-21");
  assert.equal(result.endDate, "2026-07-23");
  assert.equal(result.entries.length, 6);
  assert.equal(result.entries[0].description, "01 Transports (occasional) — 2026-07-21");
});

test("les repas cumulés identiques sont fusionnés", () => {
  const result = parser.parseEmail(sampleEmail);
  const combined = result.entries.find((entry) =>
    entry.date === "2026-07-22" && entry.label.includes("Lnch+Dnnr")
  );

  assert.equal(combined.amount, 44.82);
  assert.equal(combined.reimbursableAmount, 44.82);
});

test("le montant remboursable et le commentaire sont prioritaires", () => {
  const result = parser.parseEmail(sampleEmail);
  const dinner = result.entries.find((entry) => entry.label === "06 Dinner Paris");

  assert.equal(dinner.amount, 20.48);
  assert.equal(dinner.reimbursableAmount, 14.48);
  assert.equal(dinner.description, "Dîner équipe");
});

test("le CSV fournit descriptions et noms des pièces jointes", () => {
  const csv = `\uFEFFD\u00e9placement;Les Clayes
D\u00e9but;2026-07-21
Fin;2026-07-21

Date;Code;Libell\u00e9;Montant TTC;Montant remboursable;Commentaire / Description;Pi\u00e8ce jointe
2026-07-21;02;Taxi;29,95;29,95;Gare vers client;02_Taxi_2026-07-21.pdf
`;
  const result = parser.parseCsv(csv);

  assert.equal(result.source, "csv");
  assert.equal(result.tripName, "Les Clayes");
  assert.deepEqual(result.entries[0], {
    date: "2026-07-21",
    code: "02",
    label: "02 Taxi",
    amount: 29.95,
    reimbursableAmount: 29.95,
    description: "Gare vers client",
    attachments: ["02_Taxi_2026-07-21.pdf"]
  });
});

test("les cellules CSV contenant un point-virgule sont reconnues", () => {
  const rows = parser.parseCsvRows('A;B\n"Taxi; retour";"Client ""A"""\n');
  assert.deepEqual(rows[1], ["Taxi; retour", 'Client "A"']);
});
