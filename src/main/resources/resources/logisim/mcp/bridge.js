/*
 * Logisim-evolution - Peler's Edition
 * stdio-to-HTTP bridge for the embedded MCP server.
 *
 * MCP Bundles only describe local processes: manifest.json says "run this command" and the host
 * talks to it over stdin and stdout. The server this bundle is for is not a process to launch --
 * it lives inside a running Logisim window, reachable over HTTP on the loopback interface. Which
 * is the point: the circuit the tools edit is the one on screen, being drawn while you watch. So
 * the bundle ships this instead of a server: read a JSON-RPC line, POST it, write the reply back.
 *
 * Two failures matter more than they look:
 *
 *   Logisim is not running. The host started this process and expects it to stay up, so a refused
 *   connection cannot be fatal -- it answers the one request and waits. Close Logisim, reopen it,
 *   and the next tool call works with nothing reinstalled.
 *
 *   The session is gone. Sessions live in the Logisim process, so restarting it invalidates the id
 *   this bridge holds and every later request answers 404. The initialize request is kept so it can
 *   be replayed to open a fresh session, and the request that hit the 404 is retried once.
 *
 * stdout carries the protocol and nothing else. Diagnostics go to stderr.
 */

'use strict';

const http = require('node:http');
const https = require('node:https');
const readline = require('node:readline');
const { URL } = require('node:url');

const ENDPOINT = process.env.LOGISIM_MCP_URL || 'http://127.0.0.1:8765/mcp';
const TOKEN = process.env.LOGISIM_MCP_TOKEN || '';

/** The id issued by the last successful initialize, or null before one has happened. */
let sessionId = null;
/** The initialize request itself, kept so a lost session can be reopened without the client. */
let initializeRequest = null;
/** Single-flight guard: concurrent 404s must reopen one session, not one each. */
let reopening = null;

/**
 * One HTTP round trip. Resolves with {status, headers, body}; rejects only if the connection
 * itself failed, which the caller turns into a JSON-RPC error rather than a crash.
 */
function post(payload, extraHeaders) {
  return new Promise((resolve, reject) => {
    const target = new URL(ENDPOINT);
    const body = Buffer.from(JSON.stringify(payload), 'utf8');
    const headers = Object.assign(
      {
        'Content-Type': 'application/json',
        // The server answers POST with plain JSON and keeps event streams on GET, which this
        // bridge does not open. Both types are advertised so it stays correct if that changes.
        Accept: 'application/json, text/event-stream',
        'Content-Length': body.length,
      },
      extraHeaders || {}
    );
    if (TOKEN) headers.Authorization = 'Bearer ' + TOKEN;

    const transport = target.protocol === 'https:' ? https : http;
    const request = transport.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port,
        path: target.pathname + target.search,
        method: 'POST',
        headers,
      },
      (response) => {
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('end', () =>
          resolve({
            status: response.statusCode,
            headers: response.headers,
            body: Buffer.concat(chunks).toString('utf8'),
          })
        );
      }
    );
    request.on('error', reject);
    request.write(body);
    request.end();
  });
}

/** Reopens a session by replaying the stored initialize. Returns false if that is not possible. */
async function reopenSession() {
  if (!initializeRequest) return false;
  if (!reopening) {
    reopening = (async () => {
      const response = await post(initializeRequest, {});
      if (response.status !== 200) return false;
      const issued = response.headers['mcp-session-id'];
      if (!issued) return false;
      sessionId = issued;
      process.stderr.write('logisim-mcp-bridge: reopened session after a restart\n');
      return true;
    })().finally(() => {
      reopening = null;
    });
  }
  return reopening;
}

function writeMessage(message) {
  // One message per line, and no embedded newlines: re-serialising rather than forwarding the
  // response bytes is what guarantees that, whatever the server's formatting happens to be.
  process.stdout.write(JSON.stringify(message) + '\n');
}

function writeError(id, code, message) {
  if (id === undefined || id === null) return; // a notification gets no reply, even a failing one
  writeMessage({ jsonrpc: '2.0', id, error: { code, message } });
}

async function forward(request, retried) {
  const id = request.id;
  const isInitialize = request.method === 'initialize';
  const headers = {};
  if (!isInitialize && sessionId) headers['Mcp-Session-Id'] = sessionId;

  let response;
  try {
    response = await post(request, headers);
  } catch (e) {
    writeError(
      id,
      -32001,
      'Logisim is not reachable at ' +
        ENDPOINT +
        '. Start Logisim-evolution (Peler\'s Edition) and switch on ' +
        'Preferences -> Peler\'s Features -> MCP. (' +
        e.code +
        ')'
    );
    return;
  }

  if (response.status === 202) return; // notification accepted, nothing to say

  // The session died with the Logisim process that held it. Reopen and try the request once more.
  if ((response.status === 404 || response.status === 400) && !retried && !isInitialize) {
    if (await reopenSession()) {
      await forward(request, true);
      return;
    }
  }

  if (response.status === 401) {
    writeError(
      id,
      -32002,
      'Logisim rejected the MCP token. The token changes if preferences are reset, so export a ' +
        'fresh bundle from Logisim: MCP -> Export MCP Bundle.'
    );
    return;
  }

  let parsed;
  try {
    parsed = JSON.parse(response.body);
  } catch (e) {
    writeError(id, -32603, 'Logisim returned a malformed reply (HTTP ' + response.status + ')');
    return;
  }

  if (isInitialize && response.status === 200) {
    const issued = response.headers['mcp-session-id'];
    if (issued) sessionId = issued;
    initializeRequest = request;
  }

  writeMessage(parsed);
}

const input = readline.createInterface({ input: process.stdin });
input.on('line', (line) => {
  const text = line.trim();
  if (!text) return;
  let request;
  try {
    request = JSON.parse(text);
  } catch (e) {
    writeMessage({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
    return;
  }
  // Not awaited: a long tool call must not hold up a ping or a cancellation behind it.
  forward(request, false).catch((e) => {
    writeError(request.id, -32603, 'MCP bridge failure: ' + e.message);
  });
});
input.on('close', () => process.exit(0));
