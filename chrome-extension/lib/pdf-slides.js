// Turns a PDF of the whole deck into one image per slide, using Mozilla's pdf.js (vendor/pdfjs).
//
// Exposes PresentationGlass.pdfSlides.render(arrayBuffer, options, callbacks):
//   options    { width, quality, order } width in pixels, JPEG quality 0-1, and a function that
//              gets the page count and returns page indexes in the order to render them (to send
//              the current slide first).
//   callbacks  { onCount(count), onSlide(index, { title, data }) }. onCount is called once before
//              any page. onSlide is called for each page as soon as it is ready. data is a JPEG as
//              base64, and title is the first line of text on the page.
//
// Downloading one PDF instead of one image per slide keeps the number of requests to the slide
// service at one per load. Google refuses downloads that come too often (HTTP 429).
//
// pdf.js normally runs its parser in a Web Worker. A content script cannot start a worker from
// the extension, so vendor/pdfjs/pdf.worker.min.js is loaded as a content script too. pdf.js then
// finds it (globalThis.pdfjsWorker) and parses on the page's thread instead.

(() => {
  const TITLE_MAX_CHARS = 40;

  function toBase64(bytes) {
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }

  async function firstLine(page) {
    const content = await page.getTextContent();
    const text = content.items.map((item) => item.str.trim()).find((str) => str.length > 0);
    return (text || "").slice(0, TITLE_MAX_CHARS);
  }

  async function renderPage(page, width, quality) {
    const base = page.getViewport({ scale: 1 });
    const viewport = page.getViewport({ scale: width / base.width });
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(viewport.width);
    canvas.height = Math.round(viewport.height);
    await page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
    const blob = await new Promise((done) => canvas.toBlob(done, "image/jpeg", quality));
    return toBase64(new Uint8Array(await blob.arrayBuffer()));
  }

  async function render(arrayBuffer, { width, quality, order }, { onCount, onSlide }) {
    const pdf = await globalThis.pdfjsLib.getDocument({ data: new Uint8Array(arrayBuffer) })
      .promise;
    try {
      onCount(pdf.numPages);
      for (const index of order(pdf.numPages)) {
        const page = await pdf.getPage(index + 1);
        const [title, data] = await Promise.all([firstLine(page), renderPage(page, width, quality)]);
        page.cleanup();
        onSlide(index, { title, data });
      }
    } finally {
      pdf.destroy();
    }
  }

  globalThis.PresentationGlass = globalThis.PresentationGlass || { adapters: [] };
  globalThis.PresentationGlass.pdfSlides = { render };
})();
