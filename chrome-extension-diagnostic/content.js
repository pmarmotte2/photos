(() => {
  const MAX_CONTROLS = 1500;
  const MAX_TEXT_LENGTH = 180;
  const CONTROL_SELECTOR = [
    "input",
    "select",
    "textarea",
    "button",
    "[contenteditable='true']",
    "[role='button']",
    "[role='checkbox']",
    "[role='combobox']",
    "[role='listbox']",
    "[role='option']",
    "[role='radio']",
    "[role='spinbutton']",
    "[role='textbox']",
    "[role='menuitem']",
    "[data-sap-ui]"
  ].join(",");

  function compactText(value) {
    return String(value || "")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, MAX_TEXT_LENGTH);
  }

  function safePageUrl(locationObject) {
    return `${locationObject.origin}${locationObject.pathname}`;
  }

  function isVisible(element) {
    const style = element.ownerDocument.defaultView?.getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return Boolean(
      style &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        Number(style.opacity) !== 0 &&
        rect.width > 0 &&
        rect.height > 0
    );
  }

  function selectorPath(element) {
    if (element.id) {
      return `#${CSS.escape(element.id)}`;
    }
    const parts = [];
    let current = element;
    while (
      current &&
      current.nodeType === Node.ELEMENT_NODE &&
      parts.length < 8
    ) {
      let part = current.localName;
      if (!part) break;
      const siblings = current.parentElement
        ? [...current.parentElement.children].filter(
            (candidate) => candidate.localName === current.localName
          )
        : [];
      if (siblings.length > 1) {
        part += `:nth-of-type(${siblings.indexOf(current) + 1})`;
      }
      parts.unshift(part);
      current = current.parentElement;
    }
    return parts.join(" > ");
  }

  function labelledByText(element) {
    const ids = compactText(element.getAttribute("aria-labelledby")).split(" ");
    if (!ids[0]) return "";
    return compactText(
      ids
        .map((id) => element.ownerDocument.getElementById(id)?.textContent)
        .filter(Boolean)
        .join(" ")
    );
  }

  function explicitLabel(element) {
    if (element.id) {
      const label = [...element.ownerDocument.querySelectorAll("label[for]")].find(
        (candidate) => candidate.htmlFor === element.id
      );
      if (label) return compactText(label.textContent);
    }
    const wrappingLabel = element.closest("label");
    return compactText(wrappingLabel?.textContent);
  }

  function accessibleLabel(element) {
    return (
      compactText(element.getAttribute("aria-label")) ||
      labelledByText(element) ||
      explicitLabel(element) ||
      compactText(element.getAttribute("placeholder")) ||
      compactText(element.getAttribute("title"))
    );
  }

  function safeAttributes(element) {
    const names = [
      "id",
      "name",
      "type",
      "role",
      "aria-label",
      "aria-labelledby",
      "aria-describedby",
      "aria-haspopup",
      "aria-controls",
      "placeholder",
      "title",
      "autocomplete",
      "inputmode",
      "data-sap-ui",
      "data-sap-ui-render",
      "data-sap-ui-fastnavgroup"
    ];
    const attributes = {};
    for (const name of names) {
      const value = compactText(element.getAttribute(name));
      if (value) attributes[name] = value;
    }
    const classNames = [...element.classList].slice(0, 12);
    if (classNames.length) attributes.class = classNames.join(" ");
    return attributes;
  }

  function safeControlText(element) {
    const role = element.getAttribute("role");
    const allowed =
      element.localName === "button" ||
      element.localName === "option" ||
      role === "button" ||
      role === "menuitem" ||
      role === "option";
    return allowed ? compactText(element.textContent) : "";
  }

  function selectOptions(element) {
    if (element.localName !== "select" || !element.options) return undefined;
    return [...element.options]
      .slice(0, 250)
      .map((option) => compactText(option.textContent))
      .filter(Boolean);
  }

  function describeControl(element, frameIndex) {
    const rect = element.getBoundingClientRect();
    const description = {
      frameIndex,
      tag: element.localName,
      path: selectorPath(element),
      visible: isVisible(element),
      disabled:
        element.hasAttribute("disabled") ||
        element.getAttribute("aria-disabled") === "true",
      label: accessibleLabel(element),
      text: safeControlText(element),
      attributes: safeAttributes(element),
      bounds: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height)
      }
    };
    const options = selectOptions(element);
    if (options?.length) description.options = options;
    return description;
  }

  function scanDocument(documentObject, frameIndex) {
    const controls = [...documentObject.querySelectorAll(CONTROL_SELECTOR)]
      .slice(0, MAX_CONTROLS)
      .map((element) => describeControl(element, frameIndex));
    const labels = [
      ...documentObject.querySelectorAll(
        "label, legend, th, [role='columnheader']"
      )
    ]
      .map((element) => ({
        frameIndex,
        tag: element.localName,
        path: selectorPath(element),
        text: compactText(element.textContent),
        for: compactText(element.getAttribute("for"))
      }))
      .filter((label) => label.text)
      .slice(0, 750);
    return { controls, labels };
  }

  function frameDescription(frame, index) {
    let source = "";
    try {
      const url = new URL(frame.src || "about:blank", location.href);
      source =
        url.protocol === "about:" ? "about:blank" : `${url.origin}${url.pathname}`;
    } catch {
      source = "URL non lisible";
    }
    return {
      index,
      path: selectorPath(frame),
      title: compactText(frame.title),
      source,
      accessible: Boolean(frame.contentDocument)
    };
  }

  function frameworkHints() {
    const html = document.documentElement;
    return {
      sapUiBody: document.body?.classList.contains("sapUiBody") || false,
      sapUiVersionAttribute:
        compactText(html.getAttribute("data-sap-ui-version")) || null,
      sapUiControlCount: document.querySelectorAll("[data-sap-ui]").length,
      scriptPaths: [...document.scripts]
        .map((script) => {
          try {
            const url = new URL(script.src);
            return `${url.origin}${url.pathname}`;
          } catch {
            return "";
          }
        })
        .filter((path) => path && /sap|ui5|fiori/i.test(path))
        .slice(0, 30)
    };
  }

  function buildDiagnostic() {
    const frames = [...document.querySelectorAll("iframe")].map(frameDescription);
    const topLevel = scanDocument(document, 0);
    const controls = [...topLevel.controls];
    const labels = [...topLevel.labels];

    frames.forEach((frameInfo, offset) => {
      const frame = document.querySelectorAll("iframe")[offset];
      try {
        if (frame.contentDocument) {
          const scan = scanDocument(frame.contentDocument, frameInfo.index + 1);
          controls.push(...scan.controls);
          labels.push(...scan.labels);
        }
      } catch {
        frameInfo.accessible = false;
      }
    });

    return {
      diagnosticVersion: 1,
      generatedAt: new Date().toISOString(),
      privacy: {
        fieldValuesCollected: false,
        pageTextCollected: false,
        credentialsCollected: false,
        attachmentsCollected: false,
        capturedTextScope:
          "Libellés, en-têtes, boutons et options de menus uniquement"
      },
      page: {
        url: safePageUrl(location),
        language: document.documentElement.lang || navigator.language,
        viewport: {
          width: window.innerWidth,
          height: window.innerHeight,
          devicePixelRatio: window.devicePixelRatio
        }
      },
      browser: {
        userAgent: navigator.userAgent,
        locale: navigator.language
      },
      framework: frameworkHints(),
      frames,
      controls: controls.slice(0, MAX_CONTROLS),
      labels: labels.slice(0, 1000),
      truncation: {
        controlsTruncated: controls.length > MAX_CONTROLS,
        labelsTruncated: labels.length > 1000
      }
    };
  }

  function highlightControls() {
    const highlighted = [...document.querySelectorAll(CONTROL_SELECTOR)].filter(
      isVisible
    );
    highlighted.forEach((element) => {
      element.dataset.foNotesPreviousOutline = element.style.outline || "";
      element.style.outline = "3px solid #e30613";
      element.style.outlineOffset = "2px";
    });
    window.setTimeout(() => {
      highlighted.forEach((element) => {
        element.style.outline = element.dataset.foNotesPreviousOutline || "";
        element.style.outlineOffset = "";
        delete element.dataset.foNotesPreviousOutline;
      });
    }, 10_000);
    return highlighted.length;
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    try {
      if (message?.action === "scan") {
        sendResponse({ ok: true, diagnostic: buildDiagnostic() });
      } else if (message?.action === "highlight") {
        sendResponse({ ok: true, highlighted: highlightControls() });
      }
    } catch (error) {
      sendResponse({ ok: false, error: error.message });
    }
    return false;
  });
})();
