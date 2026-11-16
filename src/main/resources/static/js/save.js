// Saving on a bad connection: typing a long text and pressing Save is safe on an unstable satellite link.
// Only enhancements: without this script every form still submits as before.
//  - Every POST form: once sent, its submit buttons are disabled and the one pressed shows a spinner and "Saving…"
//    (data-busy on layout/parts :: offlineBanner; a button can say otherwise with data-busy-label), so a second press
//    sends nothing. Coming back to the page (Back, pageshow) gives the buttons back. A form whose answer is a file
//    download (data-file, the office's order export) leaves the page as it is, so it is never made busy: its
//    buttons stay usable, and no timer has to give them back.
//  - window.nnBusy(control, label, slow): the busy control, for the page scripts that send something themselves.
//    The control (a button, or a label that looks like one) is disabled and shows the spinner and the words (label,
//    else its data-busy-label, else "Saving…") in a polite live region. After 10 seconds the words become "Still
//    working. The connection is slow." (data-slow on offlineBanner; never with slow === false, for a wait that is
//    not the connection's). It returns {say(words), done()}: say changes the words and starts the 10 seconds again;
//    done puts the control back as it was. Whoever makes a control busy calls done when it ends, also on a failure.
//    The forms above use the same, so their buttons change their words after 10 seconds too.
//  - Drafts: a form with data-draft="<its page's URL>" keeps its fields in localStorage while the user types, under
//    that URL and the signed-in user (<body data-user>), so another user on the same PC never sees it. Never file
//    inputs, passwords or hidden fields, nor a field marked data-draft-skip. Opening the form with a draft that differs
//    from what the server sent puts the draft back and says so ("Draft restored from 12:40 UTC · Discard", the
//    template layout/parts :: draftNotice); Discard puts back what the server sent. The draft is removed only when the
//    server confirms the save: sending the form (or a form with data-draft-for="<the same URL>", e.g. the announcement
//    review page) notes the draft in sessionStorage, and the next page removes it if it shows the success message
//    ([data-flash="success"]). Any other page (the form with errors, the sign-in page after the session expired)
//    keeps it, so after signing in again the form opens with the text.
//  - Leaving a page never asks anything: text typed in a form without data-draft and not sent is lost, as on any web
//    form.
//  - Offline: while the browser reports no connection, a banner at the bottom says so (layout/parts :: offlineBanner)
//    and sending a POST form is stopped with a message next to its button, instead of a failed request.
// Storage can be full, blocked or missing (private windows, strict settings): every access is wrapped, and without it
// the page works as before, just without drafts.
(function () {
    'use strict';

    const banner = document.getElementById('offlineBanner');
    const notice = document.getElementById('draftNotice');
    const user = document.body.dataset.user || '';
    const busyLabel = (banner && banner.dataset.busy) || 'Saving…';
    const slowLabel = (banner && banner.dataset.slow) || '';
    const SLOW_MS = 10000;
    const DRAFT = 'nn.draft.';
    const PENDING = 'nn.draft.pending';
    const lang = document.documentElement.lang || undefined;

    // ---- Storage, never trusted to be there --------------------------------------------------------------------

    function store(name) {
        try {
            const storage = window[name];
            const probe = DRAFT + 'probe';
            storage.setItem(probe, probe);
            storage.removeItem(probe);
            return storage;
        } catch (e) {
            return null;
        }
    }

    const local = store('localStorage');
    const session = store('sessionStorage');

    function read(storage, key) {
        if (!storage) {
            return null;
        }
        try {
            const value = storage.getItem(key);
            return value ? JSON.parse(value) : null;
        } catch (e) {
            return null;
        }
    }

    function write(storage, key, value) {
        if (!storage) {
            return;
        }
        try {
            storage.setItem(key, JSON.stringify(value));
        } catch (e) {
            // full or blocked: the text is still in the form
        }
    }

    function remove(storage, key) {
        if (!storage) {
            return;
        }
        try {
            storage.removeItem(key);
        } catch (e) {
            // blocked
        }
    }

    function draftKey(url) {
        return DRAFT + user + ':' + url;
    }

    // ---- Form fields ----------------------------------------------------------------------------------------------

    const NOT_TYPED = ['hidden', 'password', 'file', 'submit', 'button', 'reset', 'image'];

    function isSubmit(element) {
        return (element.tagName === 'BUTTON' && element.type === 'submit')
            || (element.tagName === 'INPUT' && (element.type === 'submit' || element.type === 'image'));
    }

    // What the user typed or chose: text fields, text areas, selects, checkboxes and radios.
    function userFields(form) {
        return Array.from(form.elements).filter(element => element.name && !element.disabled
            && (element.tagName === 'TEXTAREA' || element.tagName === 'SELECT'
                || (element.tagName === 'INPUT' && !NOT_TYPED.includes(element.type))));
    }

    function draftFields(form) {
        return userFields(form).filter(element => !element.hasAttribute('data-draft-skip'));
    }

    // { name: [values] }: the values of the fields with that name in page order; for checkboxes and radios the
    // checked values; for a select the selected values.
    function valuesOf(fields) {
        const values = {};
        fields.forEach(element => {
            const list = values[element.name] || (values[element.name] = []);
            if (element.type === 'checkbox' || element.type === 'radio') {
                if (element.checked) {
                    list.push(element.value);
                }
            } else if (element.tagName === 'SELECT') {
                Array.from(element.selectedOptions).forEach(option => list.push(option.value));
            } else {
                list.push(element.value);
            }
        });
        return values;
    }

    function same(a, b) {
        return JSON.stringify(a) === JSON.stringify(b);
    }

    // Puts values back into the fields and fires input and change, so the character counters (js/forms.js) follow.
    function apply(fields, values) {
        const position = {};
        fields.forEach(element => {
            const list = values[element.name];
            if (!Array.isArray(list)) {
                return;
            }
            let changed = false;
            if (element.type === 'checkbox' || element.type === 'radio') {
                const checked = list.includes(element.value);
                changed = element.checked !== checked;
                element.checked = checked;
            } else if (element.tagName === 'SELECT') {
                Array.from(element.options).forEach(option => {
                    const selected = list.includes(option.value);
                    changed = changed || option.selected !== selected;
                    option.selected = selected;
                });
            } else {
                const index = position[element.name] = (position[element.name] ?? -1) + 1;
                if (index < list.length && element.value !== list[index]) {
                    element.value = list[index];
                    changed = true;
                }
            }
            if (changed) {
                element.dispatchEvent(new Event('input', {bubbles: true}));
                element.dispatchEvent(new Event('change', {bubbles: true}));
            }
        });
    }

    // ---- The draft of the form sent last: removed once the server confirms the save -------------------------------

    (function settle() {
        const pending = read(session, PENDING);
        remove(session, PENDING);
        if (pending && pending.key && document.querySelector('[data-flash="success"]')) {
            remove(local, pending.key);
        }
    })();

    // ---- Drafts -----------------------------------------------------------------------------------------------------

    // "12:40 UTC" today, "21 Oct 2026 · 12:40 UTC" another day: the app's format for times.
    function formatTime(date) {
        const two = n => String(n).padStart(2, '0');
        const time = two(date.getUTCHours()) + ':' + two(date.getUTCMinutes()) + ' UTC';
        if (date.toISOString().slice(0, 10) === new Date().toISOString().slice(0, 10)) {
            return time;
        }
        let month;
        try {
            month = new Intl.DateTimeFormat(lang, {month: 'short', timeZone: 'UTC'}).format(date).replace('.', '');
        } catch (e) {
            month = date.toISOString().slice(5, 7);
        }
        return date.getUTCDate() + ' ' + month + ' ' + date.getUTCFullYear() + ' · ' + time;
    }

    function showNotice(form, savedAt, onDiscard) {
        if (!notice || !notice.content) {
            return;
        }
        const box = notice.content.firstElementChild.cloneNode(true);
        const text = box.querySelector('.draft-notice-text');
        const date = new Date(savedAt);
        const [before, after] = (text.dataset.template || '{time}').split('{time}');
        const time = document.createElement('time');
        time.dateTime = date.toISOString();
        time.textContent = formatTime(date);
        text.removeAttribute('data-template');
        text.append(before || '', time, after || '');
        box.querySelector('[data-draft-discard]').addEventListener('click', () => {
            onDiscard();
            box.remove();
        });
        const summary = form.querySelector(':scope > .error-summary');
        if (summary) {
            summary.after(box);
        } else {
            form.prepend(box);
        }
    }

    document.querySelectorAll('form[data-draft]').forEach(form => {
        if (form.method !== 'post' || !form.dataset.draft || !user || !local) {
            return;
        }
        const key = draftKey(form.dataset.draft);
        const fields = draftFields(form);
        const sent = valuesOf(fields); // what the server sent
        const draft = read(local, key);
        if (draft && draft.values && !same(Object.assign({}, sent, draft.values), sent)) {
            apply(fields, draft.values);
            showNotice(form, draft.savedAt, () => {
                apply(fields, sent);
                remove(local, key);
                const first = fields.find(element => element.type !== 'radio' && element.type !== 'checkbox')
                    || fields[0];
                if (first) {
                    first.focus();
                }
            });
        }
        const save = () => {
            const values = valuesOf(fields);
            if (same(values, sent)) {
                remove(local, key); // back to what the server has: nothing to keep
            } else {
                write(local, key, {savedAt: Date.now(), values: values});
            }
        };
        form.addEventListener('input', save);
        form.addEventListener('change', save);
    });

    // ---- Sending: once only, and never while offline -----------------------------------------------------------

    function offline() {
        return navigator.onLine === false;
    }

    function busy(control, label, slow) {
        const isInput = control.tagName === 'INPUT';
        const idle = isInput ? control.value : Array.from(control.childNodes);
        const width = control.style.minWidth;
        const text = document.createElement('span');
        let timer = null;
        const say = words => {
            clearTimeout(timer);
            if (isInput) {
                control.value = words;
            } else {
                text.textContent = words;
            }
            if (slow !== false && slowLabel && words !== slowLabel) {
                timer = setTimeout(() => say(slowLabel), SLOW_MS);
            }
        };
        if (!isInput) {
            control.style.minWidth = control.offsetWidth + 'px'; // the button does not shrink under the pointer
            const spinner = document.createElement('span');
            spinner.className = 'spinner-border spinner-border-sm';
            spinner.setAttribute('aria-hidden', 'true');
            text.setAttribute('role', 'status');
            control.replaceChildren(spinner, text);
        }
        control.classList.add('is-busy');
        control.disabled = true;
        say(label || control.dataset.busyLabel || busyLabel);
        return {
            say: say,
            done: () => {
                clearTimeout(timer);
                control.disabled = false;
                control.classList.remove('is-busy');
                if (isInput) {
                    control.value = idle;
                } else {
                    control.replaceChildren(...idle);
                    control.style.minWidth = width;
                }
            }
        };
    }
    window.nnBusy = busy;

    function setBusy(form, submitter) {
        const buttons = Array.from(form.elements).filter(isSubmit);
        const button = submitter && isSubmit(submitter) ? submitter : buttons[0];
        buttons.forEach(element => {
            element.disabled = true;
        });
        if (button) {
            form._busy = busy(button);
        }
    }

    function setIdle(form) {
        delete form.dataset.sending;
        if (form._busy) {
            form._busy.done();
            form._busy = null;
        }
        Array.from(form.elements).filter(isSubmit).forEach(button => {
            button.disabled = false;
        });
    }

    function blockedMessage(form, submitter) {
        form.querySelectorAll('.save-blocked').forEach(message => message.remove());
        const message = document.createElement('p');
        message.className = 'save-blocked';
        message.setAttribute('role', 'alert');
        message.textContent = banner ? banner.dataset.blocked : '';
        const button = submitter && submitter.form === form && form.contains(submitter) ? submitter : null;
        const place = button ? (button.closest('.form-actions, .modal-footer') || button) : null;
        if (place) {
            place.after(message);
        } else if (banner) {
            // A button outside the form (form="...") or no button at all: say it in the banner.
            banner.append(' ', message);
        }
    }

    document.addEventListener('submit', event => {
        const form = event.target;
        if (event.defaultPrevented || !(form instanceof HTMLFormElement) || form.method !== 'post') {
            return;
        }
        if (form.dataset.sending) {
            event.preventDefault(); // pressed again while the first one is on its way
            return;
        }
        if (offline()) {
            event.preventDefault();
            blockedMessage(form, event.submitter);
            return;
        }
        if (form.hasAttribute('data-file')) {
            return; // a download: the page stays, so nothing would give the buttons back
        }
        form.dataset.sending = 'true';
        form.querySelectorAll('.save-blocked').forEach(message => message.remove());
        const draft = form.dataset.draft || form.dataset.draftFor;
        if (draft && user) {
            write(session, PENDING, {key: draftKey(draft)});
        }
        // After the browser has read the form: a disabled button would not be sent with it.
        const submitter = event.submitter;
        setTimeout(() => setBusy(form, submitter), 0);
    });

    // Back to this page from the browser's cache (Back button, a failed request): buttons as before.
    window.addEventListener('pageshow', event => {
        if (!event.persisted) {
            return;
        }
        document.querySelectorAll('form[data-sending]').forEach(setIdle);
        connection();
    });

    // ---- Offline banner --------------------------------------------------------------------------------------------

    function connection() {
        if (!banner) {
            return;
        }
        const isOffline = offline();
        document.documentElement.classList.toggle('is-offline', isOffline);
        if (isOffline) {
            if (!banner.textContent) {
                const drafts = user && local && document.querySelector('form[data-draft]');
                banner.textContent = drafts ? banner.dataset.text : banner.dataset.textPlain;
            }
        } else {
            banner.textContent = '';
            document.querySelectorAll('.save-blocked').forEach(message => message.remove());
        }
    }

    window.addEventListener('online', connection);
    window.addEventListener('offline', connection);
    connection();
})();
