import crypto from "node:crypto";
import http from "node:http";

// Loopback gateway used for connected-phone smoke tests. Run this script,
// `adb reverse tcp:8765 tcp:8765`, then point the debug app at
// ws://127.0.0.1:8765 with token `e2e-token`. Mobile Bash is the app's only
// MCP surface, so the client must advertise exactly `mobilebash`.

const port = Number(process.env.MOBILE_BASH_E2E_PORT ?? "8765");
const expectedToken = process.env.MOBILE_BASH_E2E_TOKEN ?? "e2e-token";

let completed = false;
const fail = (message) => {
  console.error(`E2E FAILED: ${message}`);
  process.exitCode = 1;
};

function encodeTextFrame(value) {
  const payload = Buffer.from(JSON.stringify(value));
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x81, payload.length]);
  } else if (payload.length <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}

function frameDecoder(onText) {
  let buffered = Buffer.alloc(0);
  return (chunk) => {
    buffered = Buffer.concat([buffered, chunk]);
    while (buffered.length >= 2) {
      const first = buffered[0];
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
      if (opcode === 0x1) onText(payload.toString("utf8"));
      if (opcode === 0x8) return;
    }
  };
}

const server = http.createServer((_request, response) => {
  response.writeHead(426);
  response.end("WebSocket required");
});

server.on("upgrade", (request, socket, head) => {
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
    console.log(JSON.stringify(message));
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
      completed = true;
      console.log("E2E PASSED: one-tool Mobile Bash tunnel, MCP lifecycle, composable shell, run-scoped files, Android battery bridge, and filesystem isolation");
      socket.end();
      server.close();
    }
  };

  const decode = frameDecoder(onMessage);
  socket.on("data", decode);
  if (head.length > 0) decode(head);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Mobile Bash E2E gateway listening on ws://127.0.0.1:${port}`);
});

setTimeout(() => {
  if (completed) return;
  fail("timed out waiting for the Android tunnel");
  server.close();
}, 90_000).unref();
