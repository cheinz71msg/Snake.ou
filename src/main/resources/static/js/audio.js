/**
 * Audio-Engine: erzeugt alle Sounds direkt im Browser über die Web Audio API
 * (Oszillatoren), damit keine externen Audio-Dateien benötigt werden.
 */
const GameAudio = (() => {
    let ctx = null;
    let muted = false;
    let musicNodes = null;

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

    /** Sehr einfache, prozedural erzeugte Hintergrund-Ambient-Loop (zwei leise Schwebungstöne). */
    function startMusic() {
        if (musicNodes || muted) return;
        const c = ensureContext();
        const masterGain = c.createGain();
        masterGain.gain.value = 0.05;
        masterGain.connect(c.destination);

        const osc1 = c.createOscillator();
        osc1.type = "sine";
        osc1.frequency.value = 110;
        const osc2 = c.createOscillator();
        osc2.type = "sine";
        osc2.frequency.value = 110 * 1.5;

        osc1.connect(masterGain);
        osc2.connect(masterGain);
        osc1.start();
        osc2.start();

        musicNodes = {osc1, osc2, masterGain};
    }

    function stopMusic() {
        if (!musicNodes) return;
        musicNodes.osc1.stop();
        musicNodes.osc2.stop();
        musicNodes = null;
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
