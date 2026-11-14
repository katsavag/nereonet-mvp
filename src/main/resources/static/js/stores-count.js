// Counting the store by hand (P53), stores/count.html. Loaded by that page only. Only an enhancement: without it each
// row is a small form with Save that posts and comes back to the same row.
//  - A row is sent the moment its input changes (leaving the field, Enter), with fetch and Accept: application/json
//    (StoresController.Counted), never as a whole page. The row's Save button is hidden: nothing else to press.
//  - Saved: the input shows the quantity as stored, the status badge (OK, Low, Out of stock) follows, and "Saved"
//    shows for a few seconds. Refused (422): the error under that row only, the input marked invalid, the value kept.
//  - The rows of one page are independent; a row changed again while it is being sent is sent once more after.
//  - keepalive: a row sent just as the cook leaves the page is still stored.
//  - Offline or the connection dropped: "Not saved…" under the row; changing it again sends it again. When the answer
//    is not JSON (the session expired, a 404), the row's form is sent the ordinary way, so the page says what is wrong.
(function () {
    'use strict';

    const page = document.querySelector('[data-count-page]');
    if (!page || !window.fetch) {
        return;
    }
    const notSent = page.dataset.notSent || '';
    const SAVED_MS = 3000;

    function badge(row, data) {
        const holder = row.querySelector('[data-count-status]');
        const current = holder && holder.querySelector('.status-badge');
        if (!current) {
            return;
        }
        current.className = current.className.replace(/\bstatus-(ok|warning|danger|info|neutral)\b/g, '').trim()
            + ' status-' + data.tone;
        current.textContent = data.statusLabel;
    }

    function showError(row, input, text) {
        const error = row.querySelector('[data-count-error]');
        error.textContent = text;
        error.hidden = false;
        input.classList.add('is-invalid');
        input.setAttribute('aria-invalid', 'true');
        row.querySelector('[data-count-saved]').textContent = '';
    }

    function clearError(row, input) {
        const error = row.querySelector('[data-count-error]');
        error.textContent = '';
        error.hidden = true;
        input.classList.remove('is-invalid');
        input.removeAttribute('aria-invalid');
    }

    function showSaved(row, text) {
        const saved = row.querySelector('[data-count-saved]');
        saved.textContent = text;
        clearTimeout(row._savedTimer);
        row._savedTimer = setTimeout(() => {
            saved.textContent = '';
        }, SAVED_MS);
    }

    function sendPlain(form) {
        form.dataset.plain = 'true';
        if (form.requestSubmit) {
            form.requestSubmit();
        } else {
            form.submit();
        }
    }

    function send(row, form, input) {
        if (navigator.onLine === false) {
            showError(row, input, notSent);
            return Promise.resolve();
        }
        return fetch(form.action, {
            method: 'POST',
            body: new FormData(form),
            headers: {Accept: 'application/json'},
            credentials: 'same-origin',
            keepalive: true
        }).then(response => {
            const type = response.headers.get('Content-Type') || '';
            if (type.indexOf('application/json') < 0 || (!response.ok && response.status !== 422)) {
                sendPlain(form);
                return;
            }
            return response.json().then(data => {
                if (data.error) {
                    showError(row, input, data.error);
                    return;
                }
                clearError(row, input);
                // Only when the cook has not typed something else meanwhile (that change is on its way too).
                if (input.value === form._sentValue) {
                    input.value = data.quantity;
                }
                input.defaultValue = data.quantity;
                badge(row, data);
                showSaved(row, data.message);
            });
        }).catch(() => showError(row, input, notSent));
    }

    document.querySelectorAll('[data-count-row]').forEach(row => {
        const form = row.querySelector('form[data-count-form]');
        const input = form && form.querySelector('input[name="quantity"]');
        if (!input) {
            return;
        }
        const button = form.querySelector('[data-count-save]');
        if (button) {
            button.hidden = true;
        }
        let queue = Promise.resolve();
        const save = () => {
            form._sentValue = input.value;
            queue = queue.then(() => send(row, form, input));
        };
        input.addEventListener('change', save);
        // Enter: the change event has already sent it (or nothing changed); never a whole page.
        form.addEventListener('submit', event => {
            if (form.dataset.plain) {
                return;
            }
            event.preventDefault();
        });
    });
})();
