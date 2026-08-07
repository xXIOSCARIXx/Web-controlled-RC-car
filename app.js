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

let lastBatteryMsg     = null;
let lastCarBatteryMsg  = null;
let lastWifiMsg        = null;
let lastCellMsg        = null;
let lastDataUsageMsg   = null;
let lastUsbStatusMsg   = null;
let lastGpsMsg         = null;
let lastAudioStatusMsg = null;
let lastTorchStatusMsg = null;
let lastAlarmStatusMsg = null;

function notifyPublisherViewerCount() {
    const count = viewers.size;
    const msg = JSON.stringify({ type: 'viewer_count', count });
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.send(msg);
    }
    for (const viewer of viewers) {
        if (viewer.readyState === WebSocket.OPEN) viewer.send(msg);
    }
}

const pubServer = https.createServer({ key: sslKey, cert: sslCert }, (_req, res) => {
    res.writeHead(404);
    res.end();
});

const pubWss = new WebSocket.Server({ server: pubServer, perMessageDeflate: false });

const PUBLISHER_PING_INTERVAL_MS = 5000;

function startPublisherHeartbeat(ws) {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    ws._heartbeatInterval = setInterval(() => {
        if (!ws.isAlive) {
            ws.terminate();
            return;
        }
        ws.isAlive = false;
        ws.ping();
    }, PUBLISHER_PING_INTERVAL_MS);
}

function stopPublisherHeartbeat(ws) {
    clearInterval(ws._heartbeatInterval);
}

pubWss.on('connection', (ws, _req) => {
    if (publisher && publisher.readyState === WebSocket.OPEN) {
        stopPublisherHeartbeat(publisher);
        publisher.close(1000, 'replaced');
    }
    publisher = ws;
    console.log('Publisher connected from', _req.socket.remoteAddress?.replace('::ffff:', ''));
    startPublisherHeartbeat(ws);
    notifyPublisherViewerCount();
    const connMsg = JSON.stringify({ type: 'publisher_status', connected: true });
    for (const viewer of viewers) {
        if (viewer.readyState === WebSocket.OPEN) viewer.send(connMsg);
    }

    ws.on('message', (data, isBinary) => {
        if (!isBinary) {
            try {
                const msg = JSON.parse(data.toString());

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
                if (msg.type === 'audio_status') {
                    lastAudioStatusMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastAudioStatusMsg);
                    }
                    return;
                }
                if (msg.type === 'torch_status') {
                    lastTorchStatusMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastTorchStatusMsg);
                    }
                    return;
                }
                if (msg.type === 'alarm_status') {
                    lastAlarmStatusMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastAlarmStatusMsg);
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

                if (msg.type === 'data_usage') {
                    lastDataUsageMsg = data.toString();
                    for (const viewer of viewers) {
                        if (viewer.readyState === WebSocket.OPEN) viewer.send(lastDataUsageMsg);
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
            stopPublisherHeartbeat(ws);
            publisher = null;
            lastBatteryMsg = null;
            lastCarBatteryMsg = null;
            lastWifiMsg = null;
            lastCellMsg = null;
            lastDataUsageMsg = null;
            lastUsbStatusMsg = null;
            lastGpsMsg = null;
            lastAudioStatusMsg = null;
            lastTorchStatusMsg = null;
            lastAlarmStatusMsg = null;
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
        for (const cached of [lastBatteryMsg, lastCarBatteryMsg, lastWifiMsg, lastCellMsg, lastDataUsageMsg, lastUsbStatusMsg, lastGpsMsg, lastAudioStatusMsg, lastTorchStatusMsg, lastAlarmStatusMsg]) {
            if (cached && ws.readyState === WebSocket.OPEN) {
                ws.send(cached);
            }
        }

    }

    if (publisher && publisher.readyState === WebSocket.OPEN) {
        publisher.send(JSON.stringify({ type: 'viewer_connected' }));
    }
    notifyPublisherViewerCount();

    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        const msg = data.toString();
        let parsed;
        try { parsed = JSON.parse(msg); } catch { return; }
        const type = parsed?.type;
        if (type === 'toggle_camera' || type === 'toggle_torch' || type === 'toggle_audio' || type === 'gamepad') {
            if (publisher && publisher.readyState === WebSocket.OPEN && publisher.bufferedAmount === 0) {
                publisher.send(msg);
            }
        }
        if (type === 'toggle_alarm') {
            const currentlyActive = lastAlarmStatusMsg ? JSON.parse(lastAlarmStatusMsg).enabled : false;
            const alarmMsg = JSON.stringify({ type: currentlyActive ? 'stop_alarm' : 'play_alarm' });
            if (publisher && publisher.readyState === WebSocket.OPEN) {
                publisher.send(alarmMsg);
            }
        }
        if (type === 'play_honk' || type === 'stop_honk') {
            if (publisher && publisher.readyState === WebSocket.OPEN) {
                publisher.send(msg);
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