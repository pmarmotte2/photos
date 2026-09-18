const openButton = document.querySelector("#open");
const status = document.querySelector("#status");

openButton.addEventListener("click", async () => {
  status.textContent = "";
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !tab.url?.startsWith("https://nextgen.myatos.net/")) {
    status.textContent = "Ouvrez d’abord la page ATOS.";
    return;
  }

  try {
    await chrome.tabs.sendMessage(tab.id, { type: "FO_NOTES_OPEN_FILLER" });
    window.close();
  } catch {
    status.textContent = "Rechargez la page ATOS puis réessayez.";
  }
});
