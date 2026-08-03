const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const WebSocket = require('ws');
const { execSync } = require('child_process');

const PUBLISHER_PORT = 47291;
const VIEWER_PORT    = 3000;
const HOST           = '0.0.0.0';

const SSL_DIR  = path.join(__dirname, 'ssl');
const KEY_PATH = path.join(SSL_DIR, 'key.pem');
const CRT_PATH = path.join(SSL_DIR, 'cert.pem');
const CER_PATH = path.join(SSL_DIR, 'cert.cer');

if (!fs.existsSync(KEY_PATH) || !fs.existsSync(CRT_PATH)) {
    console.log('Generating self-signed certificate...');
    fs.mkdirSync(SSL_DIR, { recursive: true });
    execSync(
        `openssl req -x509 -newkey rsa:4096 -keyout "${KEY_PATH}" -out "${CRT_PATH}" ` +
        `-days 3650 -nodes -subj "/CN=shadowscout"`,
        { stdio: 'inherit' }
    );
    execSync(`openssl x509 -in "${CRT_PATH}" -out "${CER_PATH}" -outform DER`);
    console.log(`Certificate generated. Copy ssl/cert.cer to your Android project at app/src/main/res/raw/cert.cer`);
}

const sslKey  = fs.readFileSync(KEY_PATH);
const sslCert = fs.readFileSync(CRT_PATH);

const html = fs.readFileSync(path.join(__dirname, 'UI.html'));

const viewers = new Set();
let publisher = null;

let lastBatteryMsg    = null;
let lastWifiMsg       = null;
let lastCellMsg       = null;
let lastCarBatteryMsg = null;
let lastUsbStatusMsg  = null;
let lastGpsMsg        = null;

function notifyPublisherViewerCount() {
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.send(JSON.stringify({ type: 'viewer_count', count: viewers.size }));
    }
}

const pubServer = https.createServer({ key: sslKey, cert: sslCert }, (req, res) => {
    res.writeHead(404);
    res.end();
});

const pubWss = new WebSocket.Server({ server: pubServer, perMessageDeflate: false });

pubWss.on('connection', (ws, req) => {
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.close(1000, 'replaced');
    }
    publisher = ws;
    console.log('Publisher connected from', req.socket.remoteAddress);
    notifyPublisherViewerCount();
    const connMsg = JSON.stringify({ type: 'publisher_status', connected: true });
    for (const viewer of viewers) {
        if (viewer.readyState === WebSocket.OPEN) viewer.send(connMsg);
    }

    ws.on('message', (data, isBinary) => {
        if (!isBinary) {
            try {
                const msg = JSON.parse(data.toString());
                if (msg.type === 'battery') {
                    lastBatteryMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastBatteryMsg);
                    }
                    return;
                }
                if (msg.type === 'wifi') {
                    lastWifiMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastWifiMsg);
                    }
                    return;
                }
                if (msg.type === 'cell') {
                    lastCellMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastCellMsg);
                    }
                    return;
                }
                if (msg.type === 'car_battery') {
                    lastCarBatteryMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastCarBatteryMsg);
                    }
                    return;
                }
                if (msg.type === 'usb_status') {
                    lastUsbStatusMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastUsbStatusMsg);
                    }
                    return;
                }
                if (msg.type === 'gps') {
                    lastGpsMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastGpsMsg);
                    }
                    return;
                }
            } catch {}

            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(data, { binary: false });
            }
            return;
        }

        for (const viewer of viewers) {
            if (viewer.readyState === WebSocket.OPEN && viewer.bufferedAmount === 0) {
                viewer.send(data, { binary: true });
            }
        }
    });

    ws.on('close', () => {
        if (publisher === ws) {
            publisher = null;
            console.log('Publisher disconnected');
            const msg = JSON.stringify({ type: 'publisher_disconnected' });
            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(msg);
            }
        }
    });
});

pubServer.listen(PUBLISHER_PORT, HOST, () => {
    console.log(`Publisher WSS listening on wss://${HOST}:${PUBLISHER_PORT}`);
});

const viewServer = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(html);
        return;
    }
    res.writeHead(404);
    res.end('Not found');
});

const viewWss = new WebSocket.Server({ server: viewServer, perMessageDeflate: false });

viewWss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress?.replace('::ffff:', '');

    viewers.add(ws);
    console.log('Viewer connected from', clientIp);

    const publisherIsConnected = !!(publisher && publisher.readyState === WebSocket.OPEN);
    ws.send(JSON.stringify({ type: 'publisher_status', connected: publisherIsConnected }));

    if (publisherIsConnected) {
        for (const cached of [lastBatteryMsg, lastWifiMsg, lastCellMsg, lastCarBatteryMsg, lastUsbStatusMsg, lastGpsMsg]) {
            if (cached && ws.readyState === WebSocket.OPEN) {
                ws.send(cached);
            }
        }
    }

    notifyPublisherViewerCount();
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.send(JSON.stringify({ type: 'viewer_connected' }));
    }

    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        const msg = data.toString();
        let parsed;
        try { parsed = JSON.parse(msg); } catch { return; }
        const type = parsed?.type;
        if (type === 'toggle_camera' || type === 'toggle_torch' || type === 'gamepad') {
            if (publisher && publisher.readyState === WebSocket.OPEN && publisher.bufferedAmount === 0) {
                publisher.send(msg);
            }
        }
        if (type === 'gps') {
            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(msg);
            }
        }
    });

    ws.on('close', () => {
        viewers.delete(ws);
        console.log('Viewer disconnected from', clientIp);
        notifyPublisherViewerCount();
    });
});

viewServer.listen(VIEWER_PORT, HOST, () => {
    console.log(`Viewer WS listening on http://${HOST}:${VIEWER_PORT}`);
});