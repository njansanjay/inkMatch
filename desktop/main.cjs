const path = require("node:path");
const { app, BrowserWindow, dialog, Menu } = require("electron");
const { pathToFileURL } = require("node:url");

const gotSingleInstanceLock = app.requestSingleInstanceLock();

function createWindow() {
  const indexPath = path.resolve(__dirname, "..", "index.html");
  const appUrl = pathToFileURL(indexPath).href;
  const window = new BrowserWindow({
    width: 1100,
    height: 900,
    minWidth: 760,
    minHeight: 650,
    title: "InkMatch",
    backgroundColor: "#fcfcfa",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  window.webContents.on("will-navigate", (event, url) => {
    if (url !== appUrl) event.preventDefault();
  });

  window.loadFile(indexPath).catch((error) => {
    console.error("Unable to load InkMatch:", error);
    dialog.showErrorBox("InkMatch could not start", "The app interface could not be loaded.");
    app.quit();
  });

  return window;
}

if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.whenReady()
    .then(() => {
      Menu.setApplicationMenu(Menu.buildFromTemplate([
        {
          label: "File",
          submenu: [{ label: "Exit", role: "quit" }],
        },
        {
          label: "Edit",
          submenu: [
            { role: "undo" },
            { role: "redo" },
            { type: "separator" },
            { role: "cut" },
            { role: "copy" },
            { role: "paste" },
            { role: "selectAll" },
          ],
        },
        {
          label: "View",
          submenu: [
            { role: "reload" },
            { role: "togglefullscreen" },
          ],
        },
        {
          label: "Help",
          submenu: [{
            label: "About InkMatch",
            click: () => dialog.showMessageBox({
              type: "info",
              title: "About InkMatch",
              message: "InkMatch Desktop",
              detail: "A private handwriting comparison tool. Images stay on your device.",
            }),
          }],
        },
      ]));
      createWindow();

      app.on("activate", () => {
        if (BrowserWindow.getAllWindows().length === 0) {
          createWindow();
        }
      });
    })
    .catch((error) => {
      console.error("Unable to start InkMatch:", error);
      dialog.showErrorBox("InkMatch could not start", "The desktop app could not be initialized.");
      app.quit();
    });

  app.on("window-all-closed", () => {
    if (process.platform !== "darwin") app.quit();
  });
}
