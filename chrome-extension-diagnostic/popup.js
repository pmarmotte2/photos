let latestDiagnostic = null;

const scanButton = document.querySelector("#scan");
const resultSection = document.querySelector("#result");
const statusElement = document.querySelector("#status");
const controlCount = document.querySelector("#controlCount");
const labelCount = document.querySelector("#labelCount");
const frameCount = document.querySelector("#frameCount");

function setStatus(message, kind = "") {
  statusElement.textContent = message;
  statusElement.className = kind;
}

async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) {
    throw new Error("Aucun onglet actif n’a été trouvé.");
  }
  if (!tab.url?.startsWith("https://nextgen.myatos.net/")) {
    throw new Error("Ouvrez d’abord le formulaire sur nextgen.myatos.net.");
  }
  return tab;
}

async function sendToPage(message) {
  const tab = await activeTab();
  try {
    return await chrome.tabs.sendMessage(tab.id, message);
  } catch {
    throw new Error(
      "L’extension ne peut pas lire cette page. Rechargez l’onglet ATOS puis réessayez."
    );
  }
}

scanButton.addEventListener("click", async () => {
  scanButton.disabled = true;
  setStatus("Analyse de la structure en cours…");
  try {
    const response = await sendToPage({ action: "scan" });
    if (!response?.ok) {
      throw new Error(response?.error || "Le diagnostic a échoué.");
    }
    latestDiagnostic = response.diagnostic;
    controlCount.textContent = String(latestDiagnostic.controls.length);
    labelCount.textContent = String(latestDiagnostic.labels.length);
    frameCount.textContent = String(latestDiagnostic.frames.length);
    resultSection.hidden = false;
    setStatus(
      "Diagnostic prêt. Téléchargez le JSON et transmettez uniquement ce fichier.",
      "success"
    );
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    scanButton.disabled = false;
  }
});

document.querySelector("#highlight").addEventListener("click", async () => {
  try {
    await sendToPage({ action: "highlight" });
    setStatus("Les éléments analysés sont surlignés pendant 10 secondes.", "success");
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#copy").addEventListener("click", async () => {
  if (!latestDiagnostic) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify(latestDiagnostic, null, 2));
    setStatus("Diagnostic copié dans le presse-papiers.", "success");
  } catch {
    setStatus("La copie a échoué. Utilisez le téléchargement JSON.", "error");
  }
});

document.querySelector("#download").addEventListener("click", () => {
  if (!latestDiagnostic) return;
  const content = JSON.stringify(latestDiagnostic, null, 2);
  const url = URL.createObjectURL(
    new Blob([content], { type: "application/json;charset=utf-8" })
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `fo-notes-diagnostic-${new Date()
    .toISOString()
    .replace(/[:.]/g, "-")}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
  setStatus("Diagnostic JSON téléchargé.", "success");
});
