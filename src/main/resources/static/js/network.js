/**
 * Kümmert sich um die WebSocket-Verbindung zum Server.
 * Stellt Funktionen zum Beitreten, Steuern und Verlassen bereit sowie
 * Callbacks für eingehende Nachrichten (Zustand, Tod).
 */
const Network = (() => {
    let socket = null;
    let onState = () => {};
    let onDeath = () => {};
    let onJoined = () => {};

    function connect() {
        return new Promise((resolve, reject) => {
            // SockJS verwendet normale http(s)-URLs (kein ws://) und wählt automatisch
            // den besten Transportweg. Falls eine Firewall/Security-Software reine
            // WebSockets blockiert, weicht SockJS automatisch auf HTTP-Streaming/Polling aus.
            const url = `${location.protocol}//${location.host}/ws/game`;
            console.log("[Network] Verbinde zu", url);

            let settled = false;
            const timeout = setTimeout(() => {
                if (!settled) {
                    settled = true;
                    console.error("[Network] Zeitüberschreitung beim Verbindungsaufbau zu", url);
                    reject(new Error("timeout"));
                }
            }, 8000);

            try {
                socket = new SockJS(url);
            } catch (err) {
                clearTimeout(timeout);
                console.error("[Network] Verbindung konnte nicht erstellt werden:", err);
                reject(err);
                return;
            }

            socket.onopen = () => {
                console.log("[Network] Verbindung offen, Transport:", socket.transport);
                if (!settled) {
                    settled = true;
                    clearTimeout(timeout);
                    resolve();
                }
            };
            socket.onclose = (e) => {
                console.warn("[Network] Verbindung geschlossen", e.code, e.reason);
                if (!settled) {
                    settled = true;
                    clearTimeout(timeout);
                    reject(new Error("closed before open: " + e.code));
                }
            };

            socket.onmessage = (event) => {
                const msg = JSON.parse(event.data);
                if (msg.type === "state") {
                    onState(msg);
                } else if (msg.type === "death") {
                    onDeath(msg);
                } else if (msg.type === "joined") {
                    onJoined(msg);
                }
            };
        });
    }

    function send(obj) {
        if (socket && socket.readyState === SockJS.OPEN) {
            socket.send(JSON.stringify(obj));
        }
    }

    function join(slot, name, skin) {
        send({type: "join", slot, name, skin});
    }

    function sendControl(slot, turn, boost) {
        send({type: "control", slot, turn, boost});
    }

    function leave(slot) {
        send({type: "leave", slot});
    }

    return {
        connect, join, sendControl, leave,
        set onState(fn) { onState = fn; },
        set onDeath(fn) { onDeath = fn; },
        set onJoined(fn) { onJoined = fn; },
    };
})();
