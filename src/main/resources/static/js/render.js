/**
 * Übernimmt das komplette Zeichnen: Spielwelt (Hauptkamera), Minimap
 * und Aktualisierung der Bestenliste / HUD-Texte.
 */
const Renderer = (() => {
    let canvas, ctx, minimapCanvas, minimapCtx;
    let mapWidth = 4000, mapHeight = 4000;
    let lastState = null;

    // Für flüssige Darstellung zwischen zwei Server-Updates wird linear
    // interpoliert (Server schickt z.B. nur 30 Updates/Sek., der Bildschirm
    // soll aber mit voller Bildwiederholrate, z.B. 60-144 fps, zeichnen).
    let prevState = null, currState = null;
    let prevReceivedAt = 0, currReceivedAt = 0;
    let latestMySnakeIds = {};
    let rafStarted = false;

    function init() {
        canvas = document.getElementById("gameCanvas");
        ctx = canvas.getContext("2d");
        minimapCanvas = document.getElementById("minimap");
        minimapCtx = minimapCanvas.getContext("2d");
        resize();
        window.addEventListener("resize", resize);

        if (!rafStarted) {
            rafStarted = true;
            requestAnimationFrame(drawLoop);
        }
    }

    function resize() {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
        minimapCanvas.width = minimapCanvas.clientWidth * devicePixelRatio;
        minimapCanvas.height = minimapCanvas.clientHeight * devicePixelRatio;
    }

    function findSnake(state, id) {
        return state.snakes.find(s => s.id === id);
    }

    /** Ermittelt die Kamera-Zielposition: bevorzugt Spieler 1, sonst Kartenmitte. */
    function getCameraTarget(state, mySnakeIds) {
        const id = mySnakeIds[1];
        if (id) {
            const snake = findSnake(state, id);
            if (snake && snake.segments.length > 0) {
                return {x: snake.segments[0][0], y: snake.segments[0][1]};
            }
        }
        return {x: mapWidth / 2, y: mapHeight / 2};
    }

    /** Wird bei jedem neuen Server-Update aufgerufen (ca. 30x/Sek.). */
    function renderState(state, mySnakeIds) {
        lastState = state;
        mapWidth = state.mapWidth;
        mapHeight = state.mapHeight;
        latestMySnakeIds = mySnakeIds;

        prevState = currState || state;
        prevReceivedAt = currReceivedAt || performance.now();
        currState = state;
        currReceivedAt = performance.now();

        updateLeaderboard(state, mySnakeIds);
    }

    /** Läuft mit voller Bildwiederholrate (requestAnimationFrame) und zeichnet interpolierte Positionen. */
    function drawLoop(now) {
        requestAnimationFrame(drawLoop);
        if (!currState) return;

        const tickDuration = Math.max(16, currReceivedAt - prevReceivedAt);
        const factor = Math.min(1, Math.max(0, (now - prevReceivedAt) / tickDuration));

        const interpolated = buildInterpolatedState(prevState, currState, factor);
        const camera = getCameraTarget(interpolated, latestMySnakeIds);
        drawWorld(interpolated, camera, latestMySnakeIds);
        drawMinimap(interpolated, camera, latestMySnakeIds);
    }

    /** Erzeugt einen Zwischenzustand zwischen zwei Server-Snapshots (lineare Interpolation). */
    function buildInterpolatedState(prev, curr, factor) {
        const prevById = new Map(prev.snakes.map(s => [s.id, s]));
        const snakes = curr.snakes.map(currSnake => {
            const prevSnake = prevById.get(currSnake.id);
            if (!prevSnake || prevSnake.segments.length === 0) {
                return currSnake;
            }
            const segCount = Math.min(prevSnake.segments.length, currSnake.segments.length);
            const segments = [];
            for (let i = 0; i < segCount; i++) {
                const p = prevSnake.segments[i];
                const c = currSnake.segments[i];
                segments.push([
                    p[0] + (c[0] - p[0]) * factor,
                    p[1] + (c[1] - p[1]) * factor
                ]);
            }
            // Zusätzliche (neue) Segmente am Ende unverändert übernehmen (z. B. nach Wachstum)
            for (let i = segCount; i < currSnake.segments.length; i++) {
                segments.push(currSnake.segments[i]);
            }
            return {...currSnake, segments};
        });
        return {...curr, snakes};
    }

    function worldToScreen(camera, x, y) {
        return {
            x: x - camera.x + canvas.width / 2,
            y: y - camera.y + canvas.height / 2
        };
    }

    function drawWorld(state, camera, mySnakeIds) {
        ctx.fillStyle = "#060b16";
        ctx.fillRect(0, 0, canvas.width, canvas.height);

        // Dezentes Gitter zur Tiefenwahrnehmung
        ctx.strokeStyle = "rgba(255,255,255,0.05)";
        ctx.lineWidth = 1;
        const gridSize = 100;
        const startX = Math.floor((camera.x - canvas.width / 2) / gridSize) * gridSize;
        const startY = Math.floor((camera.y - canvas.height / 2) / gridSize) * gridSize;
        for (let gx = startX; gx < camera.x + canvas.width / 2; gx += gridSize) {
            const p1 = worldToScreen(camera, gx, 0);
            ctx.beginPath();
            ctx.moveTo(p1.x, 0);
            ctx.lineTo(p1.x, canvas.height);
            ctx.stroke();
        }
        for (let gy = startY; gy < camera.y + canvas.height / 2; gy += gridSize) {
            const p1 = worldToScreen(camera, 0, gy);
            ctx.beginPath();
            ctx.moveTo(0, p1.y);
            ctx.lineTo(canvas.width, p1.y);
            ctx.stroke();
        }

        // Kartenbegrenzung (Wand)
        ctx.strokeStyle = "#ff5555";
        ctx.lineWidth = 12;
        const topLeft = worldToScreen(camera, 0, 0);
        const bottomRight = worldToScreen(camera, mapWidth, mapHeight);
        ctx.strokeRect(topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y);

        // Futter
        for (const f of state.food) {
            const p = worldToScreen(camera, f.x, f.y);
            if (p.x < -20 || p.y < -20 || p.x > canvas.width + 20 || p.y > canvas.height + 20) continue;
            ctx.beginPath();
            ctx.fillStyle = f.c;
            ctx.shadowColor = f.c;
            ctx.shadowBlur = 8;
            ctx.arc(p.x, p.y, f.r, 0, Math.PI * 2);
            ctx.fill();
            ctx.shadowBlur = 0;
        }

        const mySet = new Set(Object.values(mySnakeIds).filter(Boolean));

        // Schlangen
        for (const snake of state.snakes) {
            drawSnake(snake, camera, mySet.has(snake.id));
        }
    }

    function hexToRgb(hex) {
        const n = parseInt(hex.replace("#", ""), 16);
        return {r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255};
    }

    function lerpColor(hexA, hexB, t) {
        const a = hexToRgb(hexA), b = hexToRgb(hexB);
        const r = Math.round(a.r + (b.r - a.r) * t);
        const g = Math.round(a.g + (b.g - a.g) * t);
        const bl = Math.round(a.b + (b.b - a.b) * t);
        return `rgb(${r},${g},${bl})`;
    }

    function drawSnake(snake, camera, isMine) {
        const segs = snake.segments;
        if (segs.length === 0) return;
        const tailColor = snake.color2 || snake.color;

        // Körper (vom Schwanz zum Kopf, damit der Kopf oben liegt), mit
        // Farbverlauf von der Kopf- zur Schwanzfarbe des gewählten Skins.
        for (let i = segs.length - 1; i >= 0; i--) {
            const [wx, wy] = segs[i];
            const p = worldToScreen(camera, wx, wy);
            if (p.x < -30 || p.y < -30 || p.x > canvas.width + 30 || p.y > canvas.height + 30) continue;
            const isHead = i === 0;
            const radius = isHead ? 12 : 9;
            const t = segs.length > 1 ? i / (segs.length - 1) : 0;
            ctx.beginPath();
            ctx.fillStyle = isHead ? snake.color : lerpColor(snake.color, tailColor, t);
            ctx.globalAlpha = isHead ? 1 : 0.9;
            ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
            ctx.fill();
            ctx.globalAlpha = 1;

            if (isMine) {
                ctx.lineWidth = 2;
                ctx.strokeStyle = "#ffffff";
                ctx.stroke();
            }
        }

        // Kopf mit Augen
        const head = segs[0];
        const p = worldToScreen(camera, head[0], head[1]);
        let angle = 0;
        if (segs.length > 1) {
            angle = Math.atan2(head[1] - segs[1][1], head[0] - segs[1][0]);
        }
        const eyeOffset = 5;
        for (const side of [-1, 1]) {
            const ex = p.x + Math.cos(angle + side * 0.9) * eyeOffset;
            const ey = p.y + Math.sin(angle + side * 0.9) * eyeOffset;
            ctx.beginPath();
            ctx.fillStyle = "#ffffff";
            ctx.arc(ex, ey, 3, 0, Math.PI * 2);
            ctx.fill();
            ctx.beginPath();
            ctx.fillStyle = "#101010";
            ctx.arc(ex + Math.cos(angle) * 1.2, ey + Math.sin(angle) * 1.2, 1.4, 0, Math.PI * 2);
            ctx.fill();
        }

        // Name über dem Kopf
        ctx.font = "12px Segoe UI";
        ctx.fillStyle = "rgba(255,255,255,0.85)";
        ctx.textAlign = "center";
        ctx.fillText(snake.name, p.x, p.y - 20);
    }

    function drawMinimap(state, camera, mySnakeIds) {
        const w = minimapCanvas.width, h = minimapCanvas.height;
        minimapCtx.clearRect(0, 0, w, h);
        minimapCtx.fillStyle = "rgba(10,16,32,0.4)";
        minimapCtx.fillRect(0, 0, w, h);

        const scaleX = w / mapWidth;
        const scaleY = h / mapHeight;
        const mySet = new Set(Object.values(mySnakeIds).filter(Boolean));

        for (const snake of state.snakes) {
            if (snake.segments.length === 0) continue;
            const [x, y] = snake.segments[0];
            minimapCtx.beginPath();
            minimapCtx.fillStyle = snake.color;
            const isMine = mySet.has(snake.id);
            minimapCtx.arc(x * scaleX, y * scaleY, isMine ? 5 : 3, 0, Math.PI * 2);
            minimapCtx.fill();
            if (isMine) {
                minimapCtx.lineWidth = 1.5;
                minimapCtx.strokeStyle = "#fff";
                minimapCtx.stroke();
            }
        }

        // Sichtfeld-Rechteck
        minimapCtx.strokeStyle = "rgba(255,255,255,0.6)";
        minimapCtx.lineWidth = 1;
        const viewW = canvas.width * scaleX;
        const viewH = canvas.height * scaleY;
        minimapCtx.strokeRect(camera.x * scaleX - viewW / 2, camera.y * scaleY - viewH / 2, viewW, viewH);
    }

    function updateLeaderboard(state, mySnakeIds) {
        const list = document.getElementById("leaderboardList");
        list.innerHTML = "";
        const mySet = new Set(Object.values(mySnakeIds).filter(Boolean));
        const myNames = new Set(state.snakes.filter(s => mySet.has(s.id)).map(s => s.name));
        const medals = ["🥇", "🥈", "🥉"];

        state.leaderboard.forEach((entry, idx) => {
            const li = document.createElement("li");
            const rank = medals[idx] || `${idx + 1}.`;
            li.innerHTML = `<span class="rank">${rank}</span><span class="lb-name">${entry.name}</span><span class="lb-len">${entry.length}</span>`;
            if (myNames.has(entry.name)) {
                li.classList.add("me");
            }
            list.appendChild(li);
        });

        const p1 = findSnake(state, mySnakeIds[1]);
        const hudP1 = document.getElementById("hudP1");
        if (p1) hudP1.textContent = `Länge: ${p1.length}`;
    }

    return {init, renderState, get lastState() { return lastState; }};
})();
