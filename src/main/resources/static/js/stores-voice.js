// Counting the store by voice, the Count by voice card of stores/count.html. Loaded only when the server offers
// it. Without this script the card says that counting by voice needs JavaScript; this script removes that line and
// enables "Enable microphone".
//  - "Hold to speak" floats at the bottom right of the screen (app.css, .voice-hold). It is shown only while the
//    microphone can be used: hidden until it is enabled, and whenever the card says why voice cannot be used.
//    What goes wrong is said in the card's status line, which is scrolled into view.
//  - The microphone is asked for when the cook presses "Enable microphone", never during the first hold (the
//    browser's prompt would break the hold). Where the Permissions API already says "granted", the button is skipped.
//    The stream is closed again at once: the browser's recording light is on only while the cook holds the button.
//  - Hold to speak: recording starts on pointerdown (or Space / Enter held) and stops on pointerup, pointercancel,
//    when the pointer leaves the button, the key is released, the page is hidden, or after 2 minutes. While held: the
//    button turns red with a pulsing dot, "Release to send" and a running timer.
//  - Holding never opens the long-press menu or selects text: touch-action and user-select none (app.css), the
//    contextmenu event cancelled, and the touch's implicit pointer capture released so that leaving the button stops.
//  - Shorter than half a second: nothing is sent, "Hold the button while you speak."
//  - On release: the recording is posted to the form's action (multipart "audio", the form's CSRF token, Accept:
//    application/json) with "Processing...". The answer is the review's address (the page goes there) or the failure
//    in plain words; when sending again may help (timeout, provider error, no connection) the recording is kept and
//    "Try again" sends it again. A new hold replaces it. An answer that is not JSON (the session expired): reload.
//  - No MediaRecorder, no secure context (HTTPS outside localhost), the microphone blocked or missing: there is no
//    floating button and the card says why.
(function () {
    'use strict';

    const card = document.querySelector('[data-voice]');
    if (!card) {
        return;
    }
    const text = card.dataset;
    const form = card.querySelector('form[data-voice-form]');
    const enable = card.querySelector('[data-voice-enable]');
    const enableHint = card.querySelector('[data-voice-enable-hint]');
    const hold = card.querySelector('[data-voice-hold]');
    const label = hold.querySelector('[data-voice-label]');
    const timer = hold.querySelector('[data-voice-timer]');
    const status = card.querySelector('[data-voice-status]');
    const retry = card.querySelector('[data-voice-retry]');
    const noScript = card.querySelector('[data-voice-nojs]');
    const MIN_MS = 500;
    const MAX_MS = 120000;
    const TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];

    let pressed = false;
    let sending = false;
    let recorder = null;
    let chunks = [];
    let startedAt = 0;
    let tick = null;
    let limit = null;
    let pending = null;
    let used = false;   // the cook has pressed a button of the card: from then on its messages are scrolled to

    if (noScript) {
        noScript.remove();
    }
    enable.disabled = false;

    function say(message, tone) {
        status.textContent = message || '';
        status.dataset.tone = tone || '';
        // The button floats at the bottom of the screen: what went wrong must not stay out of sight at the top.
        if (used && message && (tone === 'error' || tone === 'hint' || tone === 'warning')) {
            status.scrollIntoView({block: 'nearest'});
        }
    }

    function showEnable(shown) {
        enable.hidden = !shown;
        enableHint.hidden = !shown;
    }

    function off(message) {
        showEnable(false);
        hold.disabled = true;
        hold.hidden = true;
        retry.hidden = true;
        say(message, 'warning');
    }

    if (!window.isSecureContext) {
        off(text.offInsecure);
        return;
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || typeof window.MediaRecorder === 'undefined') {
        off(text.offUnsupported);
        return;
    }
    const mimeType = TYPES.find(type => MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(type)) || '';

    function ready() {
        showEnable(false);
        hold.disabled = sending;
        hold.hidden = false;
        if (status.dataset.tone === 'warning') {
            say('');
        }
    }

    function refused(error) {
        const name = error && error.name;
        if (name === 'NotAllowedError' || name === 'SecurityError') {
            off(text.offDenied);
            showEnable(true);
        } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
            off(text.offNoDevice);
            showEnable(true);
        } else {
            off(text.offUnsupported);
        }
    }

    function permission(state) {
        if (state === 'granted') {
            ready();
        } else if (state === 'denied') {
            off(text.offDenied);
        } else {
            showEnable(true);
            hold.disabled = true;
            hold.hidden = true;
        }
    }

    if (navigator.permissions && navigator.permissions.query) {
        // Not every browser knows "microphone" here: then the Enable button decides.
        navigator.permissions.query({name: 'microphone'}).then(result => {
            permission(result.state);
            result.onchange = () => permission(result.state);
        }).catch(() => {});
    }

    enable.addEventListener('click', () => {
        used = true;
        navigator.mediaDevices.getUserMedia({audio: true}).then(stream => {
            stream.getTracks().forEach(track => track.stop());
            ready();
            hold.focus();
        }).catch(refused);
    });

    function showTime() {
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        timer.textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
    }

    function idle() {
        clearInterval(tick);
        clearTimeout(limit);
        hold.classList.remove('is-recording');
        label.textContent = text.hold;
        timer.hidden = true;
        timer.textContent = '0:00';
    }

    function start() {
        if (hold.disabled || pressed || sending) {
            return;
        }
        pressed = true;
        used = true;
        startedAt = Date.now();
        retry.hidden = true;
        hold.classList.add('is-recording');
        label.textContent = text.release;
        timer.hidden = false;
        showTime();
        tick = setInterval(showTime, 250);
        say(text.recording, 'recording');
        navigator.mediaDevices.getUserMedia({audio: true}).then(stream => {
            if (!pressed) {
                // Released before the microphone was ready.
                stream.getTracks().forEach(track => track.stop());
                return;
            }
            chunks = [];
            recorder = mimeType ? new MediaRecorder(stream, {mimeType: mimeType}) : new MediaRecorder(stream);
            recorder.addEventListener('dataavailable', event => {
                if (event.data && event.data.size) {
                    chunks.push(event.data);
                }
            });
            recorder.addEventListener('stop', () => {
                stream.getTracks().forEach(track => track.stop());
                finish(recorder);
            });
            startedAt = Date.now();
            recorder.start();
            limit = setTimeout(stop, MAX_MS);
        }).catch(error => {
            pressed = false;
            idle();
            refused(error);
        });
    }

    function stop() {
        if (!pressed) {
            return;
        }
        pressed = false;
        idle();
        if (recorder && recorder.state !== 'inactive') {
            recorder.stop();
        } else {
            // Released before the microphone was ready: nothing recorded.
            say(text.tooShort, 'hint');
        }
    }

    function finish(done) {
        const held = Date.now() - startedAt;
        const type = done.mimeType || mimeType || 'audio/webm';
        const blob = new Blob(chunks, {type: type});
        recorder = null;
        chunks = [];
        if (held < MIN_MS || !blob.size) {
            say(text.tooShort, 'hint');
            return;
        }
        pending = blob;
        send();
    }

    function extension(type) {
        if (type.indexOf('mp4') >= 0) {
            return 'm4a';
        }
        return type.indexOf('ogg') >= 0 ? 'ogg' : 'webm';
    }

    function failed(message, again) {
        sending = false;
        hold.disabled = false;
        card.classList.remove('is-busy');
        if (!again) {
            pending = null;
        }
        retry.hidden = !pending;
        say(message, 'error');
    }

    function send() {
        if (!pending) {
            return;
        }
        if (navigator.onLine === false) {
            failed(text.notSent, true);
            return;
        }
        sending = true;
        hold.disabled = true;
        retry.hidden = true;
        card.classList.add('is-busy');
        say(text.processing, 'busy');
        const body = new FormData(form);
        body.append('audio', pending, 'count.' + extension(pending.type));
        fetch(form.action, {
            method: 'POST',
            body: body,
            headers: {Accept: 'application/json'},
            credentials: 'same-origin'
        }).then(response => {
            const type = response.headers.get('Content-Type') || '';
            if (type.indexOf('application/json') < 0) {
                window.location.reload();
                return;
            }
            return response.json().then(data => {
                if (response.ok && data.reviewUrl) {
                    pending = null;
                    window.location.assign(data.reviewUrl);
                    return;
                }
                failed(data.error, data.retry);
            });
        }).catch(() => failed(text.notSent, true));
    }

    retry.addEventListener('click', send);

    hold.addEventListener('pointerdown', event => {
        if (event.button !== 0) {
            return;
        }
        event.preventDefault();
        // A touch captures the pointer on its own; released, leaving the button ends the hold as with a mouse.
        if (hold.hasPointerCapture && hold.hasPointerCapture(event.pointerId)) {
            hold.releasePointerCapture(event.pointerId);
        }
        start();
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(type => hold.addEventListener(type, stop));
    hold.addEventListener('contextmenu', event => event.preventDefault());
    hold.addEventListener('keydown', event => {
        if (event.key === ' ' || event.key === 'Enter') {
            event.preventDefault();
            if (!event.repeat) {
                start();
            }
        }
    });
    hold.addEventListener('keyup', event => {
        if (event.key === ' ' || event.key === 'Enter') {
            event.preventDefault();
            stop();
        }
    });
    hold.addEventListener('blur', stop);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            stop();
        }
    });
})();
