/**
 * Audio-Engine: erzeugt alle Sounds direkt im Browser über die Web Audio API
 * (Oszillatoren), damit keine externen Audio-Dateien benötigt werden.
 */
const GameAudio = (() => {
    let ctx = null;
    let muted = false;
    let musicTimerId = null;
    /** Gemeinsamer, leiser Echo-Bus für die Hintergrundmusik (siehe startMusic). */
    let musicEchoBus = null;

    function ensureContext() {
        if (!ctx) {
            ctx = new (window.AudioContext || window.webkitAudioContext)();
        }
        if (ctx.state === "suspended") {
            ctx.resume();
        }
        return ctx;
    }

    function beep({frequency = 440, duration = 0.12, type = "sine", volume = 0.2, slideTo = null}) {
        if (muted) return;
        const c = ensureContext();
        const osc = c.createOscillator();
        const gain = c.createGain();
        osc.type = type;
        osc.frequency.setValueAtTime(frequency, c.currentTime);
        if (slideTo) {
            osc.frequency.exponentialRampToValueAtTime(slideTo, c.currentTime + duration);
        }
        gain.gain.setValueAtTime(volume, c.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, c.currentTime + duration);
        osc.connect(gain);
        gain.connect(c.destination);
        osc.start();
        osc.stop(c.currentTime + duration + 0.02);
    }

    function playEat() {
        beep({frequency: 520, slideTo: 880, duration: 0.1, type: "triangle", volume: 0.18});
    }

    function playDeath() {
        beep({frequency: 220, slideTo: 60, duration: 0.5, type: "sawtooth", volume: 0.22});
    }

    function playClick() {
        beep({frequency: 300, duration: 0.06, type: "square", volume: 0.12});
    }

    /**
     * Baut einmalig einen leisen Echo-Bus (Delay + Feedback) auf, an den
     * jede Melodienote zusätzlich zum Direktsignal angeschlossen wird. Das
     * gibt der Musik eine sanfte, räumliche Tiefe, ohne dass ein Ton
     * dauerhaft klingt - jede einzelne Note klingt wie gewohnt aus, nur mit
     * leisem Nachhall.
     */
    function getMusicEchoBus() {
        if (musicEchoBus) return musicEchoBus;
        const c = ensureContext();
        const delay = c.createDelay(2.0);
        delay.delayTime.value = 0.42;
        const feedback = c.createGain();
        feedback.gain.value = 0.3;
        const wet = c.createGain();
        wet.gain.value = 0.25;

        delay.connect(feedback);
        feedback.connect(delay);
        delay.connect(wet);
        wet.connect(c.destination);

        musicEchoBus = {input: delay};
        return musicEchoBus;
    }

    /**
     * Dezente, prozedural erzeugte Hintergrundmusik: Statt eines dauerhaft
     * klingenden Tons (früherer Ansatz: zwei endlos laufende Oszillatoren,
     * das wirkte wie ein störendes Brummen) wird in unregelmäßigen
     * Abständen eine einzelne, weich ein- und ausklingende Note aus einer
     * ruhigen Pentatonik-Tonleiter gespielt. Dazwischen ist es still -
     * das Ergebnis ist eine zurückhaltende, meditative Klangkulisse statt
     * einer aufdringlichen Dauerbeschallung.
     */
    function startMusic() {
        if (musicTimerId || muted) return;
        ensureContext();

        // A-Moll-Pentatonik über zwei Oktaven - klingt immer harmonisch,
        // egal in welcher Reihenfolge die Töne erklingen.
        const scale = [220.00, 261.63, 293.66, 329.63, 392.00, 440.00, 523.25];

        function playNote() {
            if (muted || !ctx) return;
            const c = ctx;
            const echoBus = getMusicEchoBus();
            const freq = scale[Math.floor(Math.random() * scale.length)];
            const now = c.currentTime;
            const noteDuration = 2.2;

            const osc = c.createOscillator();
            osc.type = "sine";
            osc.frequency.value = freq;

            const gain = c.createGain();
            // Weicher Einschwing-/Ausschwingvorgang statt eines harten Ein-
            // oder Aus-Schaltens - vermeidet jedes Klicken/Knacken.
            gain.gain.setValueAtTime(0.0001, now);
            gain.gain.linearRampToValueAtTime(0.045, now + 0.3);
            gain.gain.exponentialRampToValueAtTime(0.0001, now + noteDuration);

            osc.connect(gain);
            gain.connect(c.destination);
            gain.connect(echoBus.input);

            osc.start(now);
            osc.stop(now + noteDuration + 0.05);
        }

        // Töne kommen in unregelmäßigen Abständen (nicht mechanisch exakt
        // getaktet) - das wirkt organischer und weniger aufdringlich als
        // ein stures Metronom-Raster.
        function scheduleNext() {
            playNote();
            const delayMs = 1400 + Math.random() * 1600; // 1.4 - 3.0 Sekunden
            musicTimerId = setTimeout(scheduleNext, delayMs);
        }

        scheduleNext();
    }

    function stopMusic() {
        if (musicTimerId) {
            clearTimeout(musicTimerId);
            musicTimerId = null;
        }
    }

    function toggleMute() {
        muted = !muted;
        if (muted) {
            stopMusic();
        } else {
            startMusic();
        }
        return muted;
    }

    return {playEat, playDeath, playClick, startMusic, stopMusic, toggleMute, ensureContext};
})();
