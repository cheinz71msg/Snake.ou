/**
 * Verbindet Menü, Netzwerk, Rendering und Tastatureingaben miteinander.
 */
(() => {
    const mySnakeIds = {1: null};
    const previousLength = {1: null};
    let selectedSkin = SKIN_LIST[0].id;

    const menuScreen = document.getElementById("menu");
    const gameScreen = document.getElementById("gameScreen");
    const playBtn = document.getElementById("playBtn");
    const muteBtn = document.getElementById("muteBtn");

    // Skin-Auswahl im Menü aufbauen
    const skinPicker = document.getElementById("skinPicker");
    SKIN_LIST.forEach((skin, index) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "skin-swatch" + (index === 0 ? " selected" : "");
        btn.title = skin.name;
        btn.style.background = `linear-gradient(135deg, ${skin.primary}, ${skin.secondary})`;
        btn.addEventListener("click", () => {
            selectedSkin = skin.id;
            skinPicker.querySelectorAll(".skin-swatch").forEach(el => el.classList.remove("selected"));
            btn.classList.add("selected");
            GameAudio.playClick();
        });
        skinPicker.appendChild(btn);
    });

    muteBtn.addEventListener("click", () => {
        const muted = GameAudio.toggleMute();
        muteBtn.textContent = muted ? "🔇" : "🔊";
    });

    document.querySelectorAll(".back-to-menu").forEach(btn => {
        btn.addEventListener("click", () => location.reload());
    });

    playBtn.addEventListener("click", async () => {
        GameAudio.ensureContext();
        GameAudio.playClick();
        GameAudio.startMusic();

        const nameP1 = document.getElementById("nameP1").value || "Spieler";

        playBtn.disabled = true;
        playBtn.textContent = "Verbinde ...";
        const statusEl = document.getElementById("connectStatus");
        statusEl.textContent = "";

        try {
            await Network.connect();
        } catch (e) {
            console.error("Verbindungsfehler:", e);
            statusEl.textContent = "Verbindung fehlgeschlagen: " + (e && e.message ? e.message : "Unbekannter Fehler") +
                " – läuft der Server unter " + location.host + "?";
            playBtn.disabled = false;
            playBtn.textContent = "▶ Play";
            return;
        }

        Network.onJoined = (msg) => {
            console.log("[Main] Beigetreten:", msg);
            mySnakeIds[msg.slot] = msg.snakeId;
        };
        Network.onState = handleState;
        Network.onDeath = handleDeath;

        Network.join(1, nameP1, selectedSkin);

        menuScreen.hidden = true;
        gameScreen.hidden = false;
        try {
            Renderer.init();
            setupInput();
        } catch (err) {
            console.error("[Main] Fehler beim Start des Spiels:", err);
            statusEl.textContent = "Fehler beim Starten: " + err.message;
            menuScreen.hidden = false;
            gameScreen.hidden = true;
        }
    });

    function handleState(state) {
        // Prüfen, ob die eigene Schlange gewachsen ist -> Fress-Sound abspielen
        const id = mySnakeIds[1];
        if (id) {
            const snake = state.snakes.find(s => s.id === id);
            if (snake) {
                if (previousLength[1] !== null && snake.length > previousLength[1]) {
                    GameAudio.playEat();
                }
                previousLength[1] = snake.length;
            }
        }
        Renderer.renderState(state, mySnakeIds);
    }

    function handleDeath(msg) {
        if (mySnakeIds[1] === msg.snakeId) {
            GameAudio.playDeath();
            mySnakeIds[1] = null;
            const overlay = document.getElementById("gameOverP1");
            const text = document.getElementById("gameOverP1Text");
            text.textContent = `Deine Schlange "${msg.name}" erreichte eine Länge von ${msg.length}.`;
            overlay.hidden = false;
        }
    }

    function setupInput() {
        const state = {turn: 0, boost: false};

        function sendState() {
            Network.sendControl(1, state.turn, state.boost);
        }

        window.addEventListener("keydown", (e) => handleKey(e, true));
        window.addEventListener("keyup", (e) => handleKey(e, false));

        function handleKey(e, isDown) {
            let changed = false;
            switch (e.code) {
                case "ArrowLeft":
                    state.turn = isDown ? -1 : (state.turn === -1 ? 0 : state.turn);
                    changed = true;
                    break;
                case "ArrowRight":
                    state.turn = isDown ? 1 : (state.turn === 1 ? 0 : state.turn);
                    changed = true;
                    break;
                case "ArrowUp":
                    state.boost = isDown;
                    changed = true;
                    break;
                case "KeyA":
                    state.turn = isDown ? -1 : (state.turn === -1 ? 0 : state.turn);
                    changed = true;
                    break;
                case "KeyD":
                    state.turn = isDown ? 1 : (state.turn === 1 ? 0 : state.turn);
                    changed = true;
                    break;
                case "KeyW":
                    state.boost = isDown;
                    changed = true;
                    break;
            }
            if (changed) {
                sendState();
                e.preventDefault();
            }
        }
    }
})();
