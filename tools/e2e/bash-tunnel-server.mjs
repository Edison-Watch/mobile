import crypto from "node:crypto";
import http from "node:http";

// Loopback gateway used for connected-phone smoke tests. Run this script,
// `adb reverse tcp:8765 tcp:8765`, then point the debug app at
// ws://127.0.0.1:8765 with token `e2e-token`. Mobile Bash is the app's only
// MCP surface, so the client must advertise exactly `mobilebash`. Set
// MOBILE_BASH_E2E_COMPUTER=1 after enabling computer control on the phone to
// additionally verify native image/tree observations and a global Home action.

const port = Number(process.env.MOBILE_BASH_E2E_PORT ?? "8765");
const expectedToken = process.env.MOBILE_BASH_E2E_TOKEN ?? "e2e-token";
const testComputer = process.env.MOBILE_BASH_E2E_COMPUTER === "1";

let completed = false;
let failed = false;
let initialComputerObservation = null;
const sockets = new Set();
const fail = (message) => {
  if (failed) return;
  failed = true;
  console.error(`E2E FAILED: ${message}`);
  process.exitCode = 1;
};

function validateComputerObservation(result, label) {
  if (result?.isError) throw new Error(`${label} returned an MCP tool error`);
  const content = result?.content;
  const images = Array.isArray(content) ? content.filter((item) => item.type === "image") : [];
  if (images.length !== 1 || images[0].mimeType !== "image/jpeg") {
    throw new Error(`${label} did not return exactly one native MCP JPEG image block`);
  }
  const jpeg = Buffer.from(images[0].data, "base64");
  if (jpeg.length < 2 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) {
    throw new Error(`${label} image block is not JPEG data`);
  }
  const observation = result?.structuredContent?.commandResults?.[0];
  const tree = observation?.accessibilityTree;
  if (!Array.isArray(tree?.nodes) || tree.nodes.length === 0 || tree.nodeCount !== tree.nodes.length) {
    throw new Error(`${label} accessibility tree is empty or internally inconsistent`);
  }
  const screenshot = observation?.screenshot;
  if (!screenshot?.available || screenshot.encodedBytes !== jpeg.length) {
    throw new Error(`${label} screenshot metadata does not match its image block`);
  }
  const digest = crypto.createHash("sha256").update(jpeg).digest("hex");
  if (screenshot.sha256 !== digest) throw new Error(`${label} screenshot digest mismatch`);
  return observation;
}

function encodeFrame(payload, opcode = 0x1) {
  payload = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, payload.length]);
  } else if (payload.length <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}

function encodeTextFrame(value) {
  return encodeFrame(Buffer.from(JSON.stringify(value)));
}

function frameDecoder(onText, onClose, onControl) {
  let buffered = Buffer.alloc(0);
  let fragmentedOpcode = null;
  let fragments = [];
  return (chunk) => {
    buffered = Buffer.concat([buffered, chunk]);
    while (buffered.length >= 2) {
      const first = buffered[0];
      const final = (first & 0x80) !== 0;
      const second = buffered[1];
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (buffered.length < 4) return;
        length = buffered.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (buffered.length < 10) return;
        const wideLength = buffered.readBigUInt64BE(2);
        if (wideLength > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error("WebSocket frame is too large");
        length = Number(wideLength);
        offset = 10;
      }
      const maskLength = masked ? 4 : 0;
      if (buffered.length < offset + maskLength + length) return;
      const mask = masked ? buffered.subarray(offset, offset + 4) : null;
      offset += maskLength;
      const payload = Buffer.from(buffered.subarray(offset, offset + length));
      buffered = buffered.subarray(offset + length);
      if (mask) {
        for (let i = 0; i < payload.length; i += 1) payload[i] ^= mask[i % 4];
      }
      if (opcode === 0x8) { onClose(payload); return; }
      if (opcode === 0x9) { onControl(0xA, payload); continue; }
      if (opcode === 0xA) continue;
      if (opcode === 0x0) {
        if (fragmentedOpcode === null) throw new Error("unexpected continuation frame");
        fragments.push(payload);
        if (final) {
          const complete = Buffer.concat(fragments);
          if (fragmentedOpcode === 0x1) onText(complete.toString("utf8"));
          fragmentedOpcode = null;
          fragments = [];
        }
        continue;
      }
      if (opcode !== 0x1 && opcode !== 0x2) throw new Error(`unsupported WebSocket opcode ${opcode}`);
      if (fragmentedOpcode !== null) throw new Error("new data frame received before fragmented message completed");
      if (final) {
        if (opcode === 0x1) onText(payload.toString("utf8"));
      } else {
        fragmentedOpcode = opcode;
        fragments = [payload];
      }
    }
  };
}

const server = http.createServer((_request, response) => {
  response.writeHead(426);
  response.end("WebSocket required");
});

server.on("upgrade", (request, socket, head) => {
  sockets.add(socket);
  socket.once("close", () => sockets.delete(socket));
  socket.on("error", (error) => {
    if (!completed && !failed) fail(`WebSocket error: ${error.message}`);
  });
  if (request.headers.authorization !== `Bearer ${expectedToken}`) {
    fail("Bearer token was not forwarded by the app");
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return;
  }
  const key = request.headers["sec-websocket-key"];
  if (typeof key !== "string") {
    fail("missing Sec-WebSocket-Key");
    socket.destroy();
    return;
  }
  const accept = crypto
    .createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  const send = (value) => socket.write(encodeTextFrame(value));
  const requestMcp = (id, method, params = undefined) =>
    send({
      type: "mcp_frame",
      server_id: "mobilebash-e2e",
      frame: { jsonrpc: "2.0", id, method, ...(params === undefined ? {} : { params }) },
    });
  const callBash = (id, script) =>
    requestMcp(id, "tools/call", { name: "run", arguments: { script } });

  const onMessage = (raw) => {
    const message = JSON.parse(raw);
    const responseId = message.type === "mcp_frame" ? message.frame?.id : undefined;
    if (responseId === 8 || responseId === 9) {
      console.log(`received computer-use MCP response ${responseId}`);
    } else {
      console.log(JSON.stringify(message));
    }
    if (message.type === "client_hello") {
      if (JSON.stringify(message.currently_running) !== JSON.stringify(["mobilebash"])) {
        fail(`expected only mobilebash in currently_running, got ${JSON.stringify(message.currently_running)}`);
        socket.destroy();
        return;
      }
      send({
        type: "server_hello",
        protocol_version: 2,
        servers: [
          {
            server_id: "mobilebash-e2e",
            name: "mobilebash",
            command: "built-in",
            args: [],
            env: {},
            working_dir: null,
            enabled: true,
          },
        ],
      });
      return;
    }
    if (message.type === "server_spawn_result") {
      if (!message.ok) {
        fail(`mobilebash server failed to bind: ${message.error}`);
        socket.destroy();
        return;
      }
      requestMcp(1, "initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "mobile-e2e", version: "1" } });
      return;
    }
    if (message.type !== "mcp_frame") return;
    const response = message.frame;
    if (response.id === 1) {
      if (response.result?.serverInfo?.name !== "mobilebash") return fail("initialize did not identify the mobilebash server");
      requestMcp(2, "tools/list");
    } else if (response.id === 2) {
      const tools = response.result?.tools;
      if (!Array.isArray(tools) || tools.length !== 1 || tools[0].name !== "run") {
        return fail(`tools/list was not the one-tool Bash surface: ${JSON.stringify(tools)}`);
      }
      callBash(3, "if [ -e /tmp/items ]; then echo dirty; exit 9; else echo clean; fi");
    } else if (response.id === 3) {
      const text = response.result?.content?.[0]?.text ?? "";
      if (response.result?.isError || text.trim() !== "clean") return fail(`virtual filesystem was not clean at tunnel start: ${text}`);
      callBash(4, "printf 'alpha\\nbeta\\n' > /tmp/items; grep beta /tmp/items | tr a-z A-Z");
    } else if (response.id === 4) {
      const text = response.result?.content?.[0]?.text ?? "";
      if (response.result?.isError || !text.includes("BETA")) return fail(`pipeline failed: ${text}`);
      callBash(5, "cat /tmp/items | wc -l");
    } else if (response.id === 5) {
      const text = response.result?.content?.[0]?.text ?? "";
      if (response.result?.isError || text.trim() !== "2") return fail(`virtual file did not persist within the run: ${text}`);
      callBash(6, "battery status | jq '.level_percent'");
    } else if (response.id === 6) {
      const text = response.result?.content?.[0]?.text ?? "";
      if (response.result?.isError || !/^\d+(\.\d+)?$/.test(text.trim())) return fail(`Android battery command failed: ${text}`);
      callBash(7, "cat /etc/passwd");
    } else if (response.id === 7) {
      if (response.result?.isError !== true) return fail("Android filesystem escape was not rejected");
      if (testComputer) {
        callBash(8, "computer observe");
        return;
      }
      completed = true;
      console.log("E2E PASSED: one-tool Mobile Bash tunnel, MCP lifecycle, composable shell, run-scoped files, Android battery bridge, and filesystem isolation");
      clearTimeout(timeout);
      socket.end(encodeFrame(Buffer.from([0x03, 0xe8]), 0x8));
      server.close();
    } else if (response.id === 8) {
      try {
        initialComputerObservation = validateComputerObservation(response.result, "computer observe");
      } catch (error) {
        return fail(error.message);
      }
      callBash(9, "computer global home");
    } else if (response.id === 9) {
      let observation;
      try {
        observation = validateComputerObservation(response.result, "computer global home");
      } catch (error) {
        return fail(error.message);
      }
      if (observation.action?.name !== "global:home" || observation.action?.performed !== true) {
        return fail(`computer global Home was not performed: ${JSON.stringify(observation.action)}`);
      }
      if (observation.action.uiSettled !== true || observation.action.settleTimedOut !== false) {
        return fail(`computer global Home did not settle before capture: ${JSON.stringify(observation.action)}`);
      }
      if (observation.packageName === initialComputerObservation?.packageName) {
        return fail(`computer global Home returned the stale pre-action package: ${observation.packageName}`);
      }
      callBash(10, "computer click obs_missing:n0");
    } else if (response.id === 10) {
      const result = response.result;
      const text = result?.content?.find((item) => item.type === "text")?.text ?? "";
      const images = result?.content?.filter((item) => item.type === "image") ?? [];
      const error = result?.structuredContent?.commandResults?.[0];
      if (result?.isError !== true || !text.includes("stale or unknown node_id")) {
        return fail(`stale node did not return a clear MCP error: ${text}`);
      }
      if (text.length > 4096 || images.length !== 0 || error?.accessibilityTree != null) {
        return fail("stale node error included an oversized observation or image");
      }
      if (error?.ok !== false || error?.action?.performed !== false) {
        return fail(`stale node error envelope is contradictory: ${JSON.stringify(error)}`);
      }
      completed = true;
      console.log("E2E PASSED: Mobile Bash plus fresh post-action observations, native MCP screenshots/trees, and compact action errors");
      clearTimeout(timeout);
      socket.end(encodeFrame(Buffer.from([0x03, 0xe8]), 0x8));
      server.close();
    }
  };

  const decode = frameDecoder(
    onMessage,
    (payload) => socket.end(encodeFrame(payload, 0x8)),
    (opcode, payload) => socket.write(encodeFrame(payload, opcode)),
  );
  const safelyDecode = (chunk) => {
    try {
      decode(chunk);
    } catch (error) {
      fail(`invalid WebSocket payload: ${error.message}`);
      socket.destroy();
    }
  };
  socket.on("data", safelyDecode);
  if (head.length > 0) safelyDecode(head);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Mobile Bash E2E gateway listening on ws://127.0.0.1:${port}`);
});

const timeout = setTimeout(() => {
  if (completed) return;
  fail("timed out waiting for the Android tunnel");
  for (const socket of sockets) socket.destroy();
  server.close();
}, 90_000).unref();
