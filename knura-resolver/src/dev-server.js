// Runs the resolver on this computer with records kept in memory, for trying it out with a local
// knurd: node src/dev-server.js [port]. Accepts http://127.0.0.1 servers, which the real one never does.

import { createServer } from "node:http";
import { createHandler } from "./resolver.js";
import { MemoryStore } from "./memory-store.js";

const port = Number(process.argv[2] ?? 8425);
const handle = createHandler({ store: new MemoryStore(), allowLoopbackHttp: true });

createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const url = new URL(req.url, "http://localhost");
    const result = await handle({
        requestContext: { http: { method: req.method } },
        rawPath: url.pathname,
        rawQueryString: url.search.slice(1),
        body: Buffer.concat(chunks).toString("utf8"),
        isBase64Encoded: false,
    });
    res.writeHead(result.statusCode, result.headers);
    res.end(req.method === "HEAD" ? undefined : result.body);
}).listen(port, "127.0.0.1", () => console.log(`knura-resolver (dev, in memory) on http://127.0.0.1:${port}`));
