/**
 * Verbindet Menü, Netzwerk, Rendering und Tastatureingaben miteinander.
 */
(() => {
    const mySnakeIds = {1: null};
    const previousLength = {1: null};

    // Gespeicherte Vorlieben (Name, Skin) aus einem vorherigen Besuch laden,
    // damit man sie nicht bei jedem Seitenaufruf neu eingeben muss.
    // localStorage statt Cookie: einfacher in reinem Client-JS zu nutzen,
    // wird nicht bei jeder Anfrage an den Server mitgeschickt und bleibt
    // genauso dauerhaft auf dem Gerät erhalten.
    const PREFS_KEY = "snakeGame.prefs";
    function loadPrefs() {
        try {
            return JSON.parse(localStorage.getItem(PREFS_KEY)) || {};
        } catch (e) {
            return {};
        }
    }
    function savePrefs(prefs) {
        try {
            localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
        } catch (e) {
            // z. B. privater Modus/Speicher voll - einfach ignorieren,
            // Name muss dann wieder manuell eingegeben werden.
        }
    }
    const savedPrefs = loadPrefs();

    let selectedSkin = (savedPrefs.skin && SKIN_LIST.some(s => s.id === savedPrefs.skin))
        ? savedPrefs.skin
        : SKIN_LIST[0].id;

    const menuScreen = document.getElementById("menu");
    const gameScreen = document.getElementById("gameScreen");
    const playBtn = document.getElementById("playBtn");
    const muteBtn = document.getElementById("muteBtn");
    const nameInput = document.getElementById("nameP1");

    if (savedPrefs.name) {
        nameInput.value = savedPrefs.name;
    }
    nameInput.addEventListener("input", () => {
        savePrefs({name: nameInput.value, skin: selectedSkin});
    });

    // Skin-Auswahl im Menü aufbauen
    const skinPicker = document.getElementById("skinPicker");
    SKIN_LIST.forEach((skin, index) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "skin-swatch" + (skin.id === selectedSkin ? " selected" : "");
        btn.title = skin.name;
        btn.style.background = `linear-gradient(135deg, ${skin.primary}, ${skin.secondary})`;
        btn.addEventListener("click", () => {
            selectedSkin = skin.id;
            skinPicker.querySelectorAll(".skin-swatch").forEach(el => el.classList.remove("selected"));
            btn.classList.add("selected");
            GameAudio.playClick();
            savePrefs({name: nameInput.value, skin: selectedSkin});
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

        const nameP1 = nameInput.value || "Spieler";
        savePrefs({name: nameP1, skin: selectedSkin});

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

        setupKeyboard(state, sendState);
        setupTouchControls(state, sendState);
    }

    function setupKeyboard(state, sendState) {
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

    /**
     * Steuerung für Touch-Geräte (Handy/Tablet): ein virtueller Joystick
     * unten links (Finger irgendwo im Kreis platzieren und in die
     * gewünschte Richtung ziehen) und ein Boost-Knopf unten rechts.
     *
     * Der Joystick gibt die GEWÜNSCHTE WELTRICHTUNG vor (Bildschirm- und
     * Weltkoordinaten sind deckungsgleich, da die Kamera nur verschiebt,
     * nicht rotiert - siehe render.js/worldToScreen). Dieser Winkel wird
     * direkt an den Server geschickt (`Network.sendControlAngle`); der
     * Server vergleicht ihn jeden Tick mit der tatsächlichen Blickrichtung
     * der Schlange und leitet daraus die nötige Drehrichtung ab - genau wie
     * bei der KI-Steuerung (siehe `AiController.staticTurnTowards` /
     * `GameEngine.tick`).
     *
     * Wichtig: Dieser Vergleich passiert bewusst NICHT mehr im Client,
     * sondern serverseitig. Ein früherer Ansatz ließ den Client die
     * Drehentscheidung treffen (Vergleich von eigener Blickrichtung und
     * Zielrichtung, Versand von -1/0/1 wie bei der Tastatur). Das
     * funktionierte auf localhost, aber über eine echte Mobilfunk-
     * verbindung mit spürbarer Latenz entsteht bei so einem "Closed Loop
     * über das Netzwerk" zwangsläufig ein Regelkreis mit Totzeit: Der
     * Client reagiert auf einen bereits veralteten Winkel, überschießt,
     * korrigiert zurück, überschießt wieder - sichtbar als ständiges
     * Schlängeln, selbst mit Hysterese/Dead-Reckoning. Indem der Server
     * (der seinen eigenen aktuellen Winkel ohne jede Verzögerung kennt) die
     * Entscheidung trifft, ist der Regelkreis vollständig latenzfrei und
     * damit stabil.
     */
    function setupTouchControls(state, sendState) {
        // Nur auf echten Touch-Geräten aktiv werden (Feature-Detection analog
        // zur CSS-Media-Query in style.css) - auf Desktop-Geräten mit Maus
        // werden gar keine Touch-Listener registriert.
        const isTouchDevice = window.matchMedia("(hover: none), (pointer: coarse)").matches;
        if (!isTouchDevice) return;

        const joystick = document.getElementById("touchJoystick");
        const knob = document.getElementById("touchKnob");
        const boostBtn = document.getElementById("touchBoostBtn");
        if (!joystick || !knob || !boostBtn) return;

        const maxRadius = 46; // px, wie weit der Knob vom Zentrum wegwandern darf
        const deadzone = 10; // px, unterhalb dieser Auslenkung wird nicht gelenkt
        let joystickTouchId = null;
        let centerX = 0, centerY = 0;
        let desiredAngle = null;
        let smoothDX = 0, smoothDY = 0; // geglättete Richtung (gegen Finger-Zittern)
        let sendTimer = null;

        function startSendLoop() {
            if (sendTimer) return;
            sendTimer = setInterval(() => {
                if (desiredAngle === null) return;
                Network.sendControlAngle(1, desiredAngle, state.boost);
            }, 60);
        }

        function stopSendLoop() {
            if (sendTimer) {
                clearInterval(sendTimer);
                sendTimer = null;
            }
        }

        function updateKnob(dx, dy) {
            knob.style.transform = `translate(${dx}px, ${dy}px)`;
        }

        function handleJoystickMove(touch) {
            let dx = touch.clientX - centerX;
            let dy = touch.clientY - centerY;
            const dist = Math.hypot(dx, dy);
            if (dist > maxRadius) {
                dx = dx / dist * maxRadius;
                dy = dy / dist * maxRadius;
            }
            updateKnob(dx, dy);
            if (dist > deadzone) {
                // Richtung glätten (exponentiell gewichteter gleitender
                // Durchschnitt über den Einheitsvektor): Ein echter Finger
                // zittert immer leicht, was bei direkter Übernahme des
                // Rohwinkels ständig minimal wechselnde desiredAngle-Werte
                // erzeugt. Die Glättung filtert dieses Zittern heraus, ohne
                // die eigentliche Lenkrichtung spürbar zu verzögern.
                const nx = dx / dist, ny = dy / dist;
                smoothDX += (nx - smoothDX) * 0.3;
                smoothDY += (ny - smoothDY) * 0.3;
                desiredAngle = Math.atan2(smoothDY, smoothDX);
                Network.sendControlAngle(1, desiredAngle, state.boost);
            } else {
                desiredAngle = null;
                smoothDX = 0;
                smoothDY = 0;
                state.turn = 0;
                sendState(); // zurück in den turnInput-Modus (geradeaus)
            }
        }

        joystick.addEventListener("touchstart", (e) => {
            const touch = e.changedTouches[0];
            joystickTouchId = touch.identifier;
            const rect = joystick.getBoundingClientRect();
            centerX = rect.left + rect.width / 2;
            centerY = rect.top + rect.height / 2;
            handleJoystickMove(touch);
            startSendLoop();
            e.preventDefault();
        }, {passive: false});

        joystick.addEventListener("touchmove", (e) => {
            for (const touch of e.changedTouches) {
                if (touch.identifier === joystickTouchId) {
                    handleJoystickMove(touch);
                }
            }
            e.preventDefault();
        }, {passive: false});

        function endJoystickTouch(e) {
            for (const touch of e.changedTouches) {
                if (touch.identifier === joystickTouchId) {
                    joystickTouchId = null;
                    desiredAngle = null;
                    state.turn = 0;
                    sendState();
                    stopSendLoop();
                    updateKnob(0, 0);
                }
            }
        }

        joystick.addEventListener("touchend", endJoystickTouch);
        joystick.addEventListener("touchcancel", endJoystickTouch);

        // Boost darf den aktuellen Steuermodus nicht verändern: Ist der
        // Joystick gerade aktiv (Winkel-Modus), muss der boost-Wert über
        // sendControlAngle mitgeschickt werden, sonst würde sendState() das
        // manualDesiredAngle auf dem Server zurücksetzen und die Schlange
        // führe trotz gehaltenem Joystick nur noch geradeaus.
        function sendBoostState() {
            if (desiredAngle !== null) {
                Network.sendControlAngle(1, desiredAngle, state.boost);
            } else {
                sendState();
            }
        }

        boostBtn.addEventListener("touchstart", (e) => {
            state.boost = true;
            boostBtn.classList.add("active");
            sendBoostState();
            e.preventDefault();
        }, {passive: false});

        function releaseBoost(e) {
            state.boost = false;
            boostBtn.classList.remove("active");
            sendBoostState();
            e.preventDefault();
        }

        boostBtn.addEventListener("touchend", releaseBoost);
        boostBtn.addEventListener("touchcancel", releaseBoost);
    }
})();
