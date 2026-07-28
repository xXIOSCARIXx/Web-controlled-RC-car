const http = require('http');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');

const PORT = 3000;
const HOST = '0.0.0.0';

const html = fs.readFileSync(path.join(__dirname, 'UI.html'));

const server = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(html);
        return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
});

const wss = new WebSocket.Server({
    server,
    perMessageDeflate: false,
});

const viewers = new Set();
let publisher = null;

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const role = url.searchParams.get('role');

    if (role === 'publisher') {
        if (publisher && publisher.readyState === WebSocket.OPEN) {
            publisher.close(1000, 'replaced');
        }
        publisher = ws;
        console.log('Publisher connected');

        ws.on('message', (data, isBinary) => {
            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN && viewer.bufferedAmount === 0) {
                    viewer.send(data, { binary: isBinary });
                }
            }
        });

        ws.on('close', () => {
            if (publisher === ws) {
                publisher = null;
                console.log('Publisher disconnected');
            }
        });

    } else {
        viewers.add(ws);
        console.log('Viewer connected, total:', viewers.size);

        ws.on('message', (data, isBinary) => {
            if (isBinary) return;
            if (publisher && publisher.readyState === WebSocket.OPEN && publisher.bufferedAmount === 0) {
                publisher.send(data.toString());
            }
        });

        ws.on('close', () => {
            viewers.delete(ws);
            console.log('Viewer disconnected, total:', viewers.size);
        });
    }
});

server.listen(PORT, HOST, () => {
    console.log(`Server listening on http://${HOST}:${PORT}`);
});