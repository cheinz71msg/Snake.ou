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
     * nicht rotiert - siehe render.js/worldToScreen). Da der Server aber
     * weiterhin nur -1/0/1 ("links/geradeaus/rechts drehen") als
     * Eingabe kennt (wie bei der Tastatur), wird fortlaufend die aktuelle
     * Blickrichtung der eigenen Schlange (aus den letzten zwei
     * Körpersegmenten) mit der gewünschten Richtung verglichen und daraus
     * die nötige Drehrichtung abgeleitet - genau wie der Server das für
     * die KI-Steuerung tut (siehe AiController.turnTowards).
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
        let steerTimer = null;

        function getMyCurrentAngle() {
            const last = Renderer.lastState;
            const id = mySnakeIds[1];
            if (!last || !id) return null;
            const snake = last.snakes.find(s => s.id === id);
            if (!snake) return null;
            // Bevorzugt den exakten, vom Server mitgesendeten Blickwinkel
            // (snake.angle) statt ihn aus den ausgedünnten Body-Segmenten zu
            // schätzen - letzteres war ungenau/verrauscht und führte trotz
            // Hysterese noch zu sichtbarem Zickzack-Drehen.
            if (typeof snake.angle === "number") return snake.angle;
            if (!snake.segments || snake.segments.length < 2) return null;
            const head = snake.segments[0], neck = snake.segments[1];
            return Math.atan2(head[1] - neck[1], head[0] - neck[0]);
        }

        // Schmitt-Trigger-Schwellenwerte statt eines einzelnen Deadzone-Werts:
        // Verhindert das Hin-und-Her-Zittern, das entsteht, wenn die pro
        // Korrekturintervall tatsächlich gedrehte Winkelmenge (abhängig von
        // TURN_RATE_DEG_PER_SEC und der Broadcast-Verzögerung) größer ist als
        // die Deadzone selbst: Ohne Hysterese überschießt jede Korrektur über
        // die Deadzone hinaus, wird im nächsten Intervall erkannt und wieder
        // zurückgedreht -> endloses Pendeln. Mit zwei Schwellen (eng zum
        // Stoppen, weit zum erneuten Anfahren) bleibt die Schlange ruhig,
        // sobald sie grob auf Kurs ist.
        const TURN_STOP_THRESHOLD = 0.08;   // rad - unterhalb dessen wird angehalten
        const TURN_START_THRESHOLD = 0.28;  // rad - oberhalb dessen wird (wieder) gedreht

        function startSteerLoop() {
            if (steerTimer) return;
            steerTimer = setInterval(() => {
                if (desiredAngle === null) return;
                const currentAngle = getMyCurrentAngle();
                if (currentAngle === null) return;
                let diff = desiredAngle - currentAngle;
                while (diff > Math.PI) diff -= 2 * Math.PI;
                while (diff < -Math.PI) diff += 2 * Math.PI;
                const absDiff = Math.abs(diff);
                if (state.turn === 0) {
                    // steht still -> erst ab der weiten Schwelle wieder lenken
                    if (absDiff >= TURN_START_THRESHOLD) {
                        state.turn = diff > 0 ? 1 : -1;
                    }
                } else {
                    // dreht bereits -> erst ab der engen Schwelle anhalten,
                    // bis dahin ggf. Richtung an aktuelle Abweichung anpassen
                    if (absDiff < TURN_STOP_THRESHOLD) {
                        state.turn = 0;
                    } else {
                        state.turn = diff > 0 ? 1 : -1;
                    }
                }
                sendState();
            }, 60);
        }

        function stopSteerLoop() {
            if (steerTimer) {
                clearInterval(steerTimer);
                steerTimer = null;
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
                desiredAngle = Math.atan2(dy, dx);
            } else {
                desiredAngle = null;
                state.turn = 0;
                sendState();
            }
        }

        joystick.addEventListener("touchstart", (e) => {
            const touch = e.changedTouches[0];
            joystickTouchId = touch.identifier;
            const rect = joystick.getBoundingClientRect();
            centerX = rect.left + rect.width / 2;
            centerY = rect.top + rect.height / 2;
            handleJoystickMove(touch);
            startSteerLoop();
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
                    stopSteerLoop();
                    updateKnob(0, 0);
                }
            }
        }

        joystick.addEventListener("touchend", endJoystickTouch);
        joystick.addEventListener("touchcancel", endJoystickTouch);

        boostBtn.addEventListener("touchstart", (e) => {
            state.boost = true;
            boostBtn.classList.add("active");
            sendState();
            e.preventDefault();
        }, {passive: false});

        function releaseBoost(e) {
            state.boost = false;
            boostBtn.classList.remove("active");
            sendState();
            e.preventDefault();
        }

        boostBtn.addEventListener("touchend", releaseBoost);
        boostBtn.addEventListener("touchcancel", releaseBoost);
    }
})();
