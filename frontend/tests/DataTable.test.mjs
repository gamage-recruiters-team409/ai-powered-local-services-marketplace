
import assert from "node:assert/strict";
import { after, test } from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";

const server = await createServer({
  server: { middlewareMode: true },
  appType: "custom",
});

after(async () => {
  await server.close();
});

const { default: DataTable } = await server.ssrLoadModule(
  "/src/shared/components/forms/DataTable.jsx"
);

const renderTable = (props) =>
  renderToStaticMarkup(
    React.createElement(DataTable, {
      columns: [{ key: "name", header: "Name" }],
      data: [],
      ...props,
    })
  );

test("DataTable displays string errors", () => {
  const html = renderTable({ error: "Network failed" });

  assert.match(html, /Network failed/);
});

test("DataTable displays JavaScript Error messages", () => {
  const html = renderTable({
    error: new Error("Failed to fetch records"),
  });

  assert.match(html, /Failed to fetch records/);
});

test("DataTable displays fallback for unexpected errors", () => {
  const html = renderTable({
    error: { code: 500 },
  });

  assert.match(html, /Unable to load records\./);
});

test("DataTable supports custom errorContent", () => {
  const html = renderTable({
    error: new Error("Network failed"),
    errorContent: React.createElement(
      "span",
      null,
      "Custom error message"
    ),
  });

  assert.match(html, /Custom error message/);
  assert.doesNotMatch(html, /Network failed/);
});
