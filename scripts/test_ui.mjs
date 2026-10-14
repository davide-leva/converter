import { createRequire } from "node:module";
import fs from "node:fs/promises";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const base = process.env.TEST_URL || "http://localhost:8080",
  password = process.env.TEST_PASSWORD;
assert(password, "Impostare TEST_PASSWORD");
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1050 },
    acceptDownloads: true,
    locale: "it-IT",
  });
  const page = await context.newPage(),
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  async function ready() {
    await page.locator('body[data-ready="true"]').waitFor();
  }
  async function visit(path) {
    await page.goto(base + path);
    await ready();
  }
  async function login() {
    await ready();
    await page
      .locator("#login-user")
      .fill(process.env.TEST_USERNAME || "admin");
    await page.locator("#login-password").fill(password);
    await page.locator("#submit-login").click();
    await page.waitForURL("**/archivio*");
    await ready();
  }
  await page.goto(base);
  await page.waitForURL("**/accesso*");
  await login();
  const suffix = String(Date.now()).slice(-4),
    rootName = "Progetti 2026 · " + suffix;
  async function folder(name) {
    await page.locator("#new-folder").click();
    await page.locator("#folder-name").fill(name);
    const pending = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/folders") && r.request().method() === "POST",
    );
    await page.locator("#folder-save").click();
    const response = await pending;
    assert.equal(response.status(), 201);
    const record = await response.json();
    await page.locator("#folder-dialog").waitFor({ state: "hidden" });
    await page.locator(".folder-link").filter({ hasText: name }).waitFor();
    return record;
  }
  const root = await folder(rootName);
  await folder("Amministrazione · " + suffix);
  await folder("Documentazione · " + suffix);
  await page.locator(".folder-link").filter({ hasText: rootName }).click();
  await page.waitForURL("**/archivio?cartella=" + root.id);
  await ready();
  const child = await folder("Contratti");
  await page.locator(".folder-link").filter({ hasText: "Contratti" }).click();
  await page.waitForURL("**/archivio?cartella=" + child.id);
  await ready();
  await page.locator("#new-file").click();
  await page.waitForURL("**/converti?cartella=" + child.id);
  await ready();
  const names = {
    docx: "Proposta commerciale · Ottobre " + suffix,
    pptx: "Presentazione progetto " + suffix,
  };
  for (const ext of ["docx", "pptx"]) {
    await page.locator("#file").setInputFiles("target/fixtures/sample." + ext);
    await page.locator("#upload-name").fill(names[ext]);
    assert.equal(await page.locator("#upload-folder").inputValue(), child.id);
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.locator("#convert").click(),
    ]);
    assert.equal(download.suggestedFilename(), names[ext] + ".pdf");
    const content = await fs.readFile(await download.path());
    assert(content.subarray(0, 5).equals(Buffer.from("%PDF-")));
    await page.locator("#convert").waitFor({ state: "visible" });
  }
  await visit("/archivio?cartella=" + child.id);
  assert.equal(await page.locator("#count").textContent(), "2");
  // Descriptive rename and move are exercised through the real dialog.
  const word = page.locator("#rows tr").filter({ hasText: names.docx });
  await word.locator("[data-edit-document]").click();
  await page
    .locator("#document-name")
    .fill("Proposta approvata · Ottobre " + suffix);
  await page.locator("#document-folder").selectOption(root.id);
  await page.locator("#document-save").click();
  await page.locator("#document-dialog").waitFor({ state: "hidden" });
  await visit("/archivio?cartella=" + root.id);
  await page
    .locator("#rows tr")
    .filter({ hasText: "Proposta approvata · Ottobre " + suffix })
    .locator("[data-edit-document]")
    .click();
  await page.locator("#document-folder").selectOption(child.id);
  await page.locator("#document-save").click();
  await page.locator("#document-dialog").waitFor({ state: "hidden" });
  await visit("/archivio?cartella=" + child.id);
  names.docx = "Proposta approvata · Ottobre " + suffix;
  await page.locator(".doc-name").filter({ hasText: names.docx }).waitFor();
  for (const row of await page.locator("#rows tr").all()) {
    const ext = await row.locator(".download-original").textContent();
    for (const type of ["original", "pdf"]) {
      const [download] = await Promise.all([
        page.waitForEvent("download"),
        row.locator(`a[href$="/${type}"]`).click(),
      ]);
      const data = await fs.readFile(await download.path());
      if (type === "original")
        assert(
          data.equals(
            await fs.readFile("target/fixtures/sample." + ext.toLowerCase()),
          ),
        );
      else assert(data.subarray(0, 5).equals(Buffer.from("%PDF-")));
    }
  }
  await page.locator("#logout").click();
  await page.waitForURL("**/accesso");
  await login();
  await visit("/archivio?cartella=" + child.id);
  assert.equal(await page.locator("#count").textContent(), "2");
  await visit("/unisci");
  await page.locator("#merge-files").setInputFiles("target/fixtures/third.pdf");
  await page.locator("#choose-stored").click();
  for (const title of Object.values(names))
    await page
      .locator(".library-item")
      .filter({ hasText: title })
      .locator("input")
      .check();
  await page.locator("#add-stored").click();
  assert.equal(await page.locator("#queue-count").textContent(), "3");
  // Keyboard buttons and pointer drag both reorder the list.
  await page
    .locator("#merge-queue li")
    .filter({ hasText: "third.pdf" })
    .locator("[data-down]")
    .click();
  await page
    .locator("#merge-queue li")
    .filter({ hasText: "third.pdf" })
    .locator("[data-up]")
    .click();

  await page
    .locator("#merge-queue li")
    .filter({ hasText: names.pptx })
    .dragTo(page.locator("#merge-queue li").first());
  assert.equal(
    await page
      .locator("#merge-queue li")
      .first()
      .locator("strong")
      .textContent(),
    names.pptx,
  );
  let order = await page.locator("#merge-queue li strong").allTextContents();
  // Ensure the uploaded PDF sits between the two platform documents, irrespective of listing order.
  if (order[1] !== "third.pdf")
    await page
      .locator("#merge-queue li")
      .filter({ hasText: "third.pdf" })
      .locator("[data-up]")
      .click();
  order = await page.locator("#merge-queue li strong").allTextContents();
  assert.deepEqual(order, [names.pptx, "third.pdf", names.docx]);
  await page.locator("#merge-name").fill("Fascicolo progetto");
  await page.locator("#merge-folder").selectOption(child.id);
  await fs.mkdir("target/screenshots", { recursive: true });
  await page.screenshot({
    path: "target/screenshots/merge.png",
    fullPage: true,
  });
  const [merged] = await Promise.all([
    page.waitForEvent("download"),
    page.locator("#merge-submit").click(),
  ]);
  assert.equal(merged.suggestedFilename(), "Fascicolo progetto.pdf");
  await merged.saveAs("target/fixtures/browser-merged.pdf");
  if (process.env.CHECK_PDF_TEXT !== "false") {
    const text = execFileSync("pdftotext", [
      "target/fixtures/browser-merged.pdf",
      "-",
    ]).toString();
    assert(text.indexOf("Presentazione") < text.indexOf("TERZO"));
    assert(text.indexOf("TERZO") < text.indexOf("Documento"));
  }
  await visit("/archivio?cartella=" + child.id);
  assert.equal(await page.locator("#count").textContent(), "3");
  assert.equal(
    await page
      .locator(".doc-name")
      .filter({ hasText: "Fascicolo progetto" })
      .count(),
    1,
  );
  await visit("/archivio");
  await page.screenshot({
    path: "target/screenshots/desktop.png",
    fullPage: true,
  });
  for (const path of ["/archivio", "/converti", "/unisci"]) {
    await visit(path);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(
      () =>
        document.getElementById("sidebar").getBoundingClientRect().right <= 1,
    );
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      "Overflow mobile in " + path,
    );
    await page.locator("#mobile-menu").click();
    assert.equal(
      await page.locator("#mobile-menu").getAttribute("aria-expanded"),
      "true",
    );
    await page.keyboard.press("Escape");
    assert.equal(
      await page.locator("#mobile-menu").getAttribute("aria-expanded"),
      "false",
    );
    await page.waitForFunction(
      () =>
        document.getElementById("sidebar").getBoundingClientRect().right <= 1,
    );
    await page.screenshot({
      path: "target/screenshots/mobile-" + path.slice(1) + ".png",
      fullPage: true,
    });
    await page.setViewportSize({ width: 1440, height: 1050 });
  }
  assert.deepEqual(errors, []);
  console.log(
    "OK browser multipagina: cartelle/sottocartelle, conversioni, nomi descrittivi, logout/login, quattro download, merge misto riordinato con drag/frecce e layout mobile",
  );
} finally {
  await browser.close();
}
