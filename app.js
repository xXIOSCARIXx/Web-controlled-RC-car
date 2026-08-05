const fs = require('fs');
const path = require('path');
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
    fs.mkdirSync(SSL_DIR, { recursive: true });
    execSync(
        `openssl req -x509 -newkey rsa:4096 -keyout "${KEY_PATH}" -out "${CRT_PATH}" ` +
        `-days 3650 -nodes -subj "/CN=shadowscout"`,
        { stdio: 'inherit' }
    );
    execSync(`openssl x509 -in "${CRT_PATH}" -out "${CER_PATH}" -outform DER`);
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
let alarmActive       = false;

function notifyPublisherViewerCount() {
    const count = viewers.size;
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.send(JSON.stringify({ type: 'viewer_count', count }));
    }
    const msg = JSON.stringify({ type: 'viewer_count', count });
    for (const viewer of viewers) {
        if (viewer.readyState === WebSocket.OPEN) viewer.send(msg);
    }
}

const pubServer = https.createServer({ key: sslKey, cert: sslCert }, (_req, res) => {
    res.writeHead(404);
    res.end();
});

const pubWss = new WebSocket.Server({ server: pubServer, perMessageDeflate: false });

pubWss.on('connection', (ws, _req) => {
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.close(1000, 'replaced');
    }
    publisher = ws;
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
                if (msg.type === 'pong') {
                    const vid = msg._vid;
                    for (const viewer of viewers) {
                        if (viewer._pingId === vid && viewer.readyState === WebSocket.OPEN) {
                            viewer.send(data.toString());
                            break;
                        }
                    }
                    return;
                }
            } catch {}

            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(data, { binary: false });
            }
            return;
        }

        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        const isH264 = buf[0] === 0x02;

        let isKeyframe = false;
        if (isH264) {
            for (let i = 0; i < buf.length - 4; i++) {
                let scLen = 0;
                if (buf[i] === 0 && buf[i+1] === 0 && buf[i+2] === 0 && buf[i+3] === 1) scLen = 4;
                else if (buf[i] === 0 && buf[i+1] === 0 && buf[i+2] === 1) scLen = 3;
                if (scLen > 0) {
                    const nalType = buf[i + scLen] & 0x1f;
                    if (nalType === 5) { isKeyframe = true; break; }
                    if (nalType === 1) break;
                }
            }
        }

        const BACKLOG = 256 * 1024;
        for (const viewer of viewers) {
            if (viewer.readyState !== WebSocket.OPEN) continue;
            if (isH264 && !isKeyframe && viewer.bufferedAmount > BACKLOG) continue;
            viewer.send(data, { binary: true });
        }
    });

    ws.on('close', () => {
        if (publisher === ws) {
            publisher = null;
            alarmActive = false;
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

const viewServer = https.createServer({ key: sslKey, cert: sslCert }, (req, res) => {
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
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'alarm_state', active: alarmActive }));
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
        if (type === 'toggle_alarm') {
            alarmActive = !alarmActive;
            const alarmMsg = JSON.stringify({ type: alarmActive ? 'play_alarm' : 'stop_alarm' });
            if (publisher && publisher.readyState === WebSocket.OPEN) {
                publisher.send(alarmMsg);
            }
            const stateMsg = JSON.stringify({ type: 'alarm_state', active: alarmActive });
            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(stateMsg);
            }
        }
        if (type === 'gps') {
            for (const viewer of viewers) {
                if (viewer.readyState === WebSocket.OPEN) viewer.send(msg);
            }
        }
        if (type === 'ping') {
            if (publisher && publisher.readyState === WebSocket.OPEN) {
                ws._pingId = ws._pingId || (Math.random().toString(36).slice(2));
                publisher.send(JSON.stringify({ type: 'ping', ts: parsed.ts, _vid: ws._pingId }));
                ws._awaitingPong = true;
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
    console.log(`Viewer WSS listening on https://${HOST}:${VIEWER_PORT}`);
});