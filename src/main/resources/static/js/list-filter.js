// Lists (P19): the filter bar of layout/parts :: filterBar. Without this script it is an ordinary GET form with an
// Apply button (Enter in the search sends it too). With it:
//   - Apply is hidden;
//   - a filter (select, checkbox, date) applies as soon as it changes;
//   - the search applies on Enter and after a 400 ms pause in typing (not when the text is what the page shows);
//   - empty fields are left out, so the URL holds only what is set;
//   - after the new page loads, focus is back in the field that sent it, the cursor at the end of the search, so
//     typing can go on.
(() => {
    const PAUSE_MS = 400;
    const FOCUS_KEY = 'nn-list-focus';

    // Storage may be missing or blocked (private windows): then focus simply starts at the top of the page.
    const store = (value) => {
        try {
            if (value === null) {
                sessionStorage.removeItem(FOCUS_KEY);
            } else {
                sessionStorage.setItem(FOCUS_KEY, JSON.stringify(value));
            }
        } catch (e) {
            // ignore
        }
    };
    const stored = () => {
        try {
            return JSON.parse(sessionStorage.getItem(FOCUS_KEY) || 'null');
        } catch (e) {
            return null;
        }
    };

    const send = (form, field) => {
        if (form.dataset.listSending) {
            return;
        }
        form.dataset.listSending = 'true';
        store(field && field.id ? {path: location.pathname, id: field.id} : null);
        form.querySelectorAll('input, select').forEach(element => {
            if (element.name && !element.disabled && element.type !== 'checkbox' && element.value === '') {
                element.disabled = true;
                element.dataset.listEmpty = 'true';
            }
        });
        form.submit();
    };

    document.querySelectorAll('form[data-list-filter]').forEach(form => {
        form.querySelectorAll('[data-list-apply]').forEach(apply => { apply.hidden = true; });
        const search = form.querySelector('[data-list-search]');
        const shown = search ? search.value.trim() : '';
        let timer = null;

        form.addEventListener('change', event => {
            if (event.target !== search) {
                clearTimeout(timer);
                send(form, event.target);
            }
        });
        form.addEventListener('submit', event => {
            // Enter in the search field.
            event.preventDefault();
            clearTimeout(timer);
            send(form, document.activeElement);
        });
        if (search) {
            search.addEventListener('input', () => {
                clearTimeout(timer);
                timer = setTimeout(() => {
                    if (search.value.trim() !== shown) {
                        send(form, search);
                    }
                }, PAUSE_MS);
            });
        }
    });

    // Back from the next page (the browser's page cache): the form as the user left it.
    window.addEventListener('pageshow', () => {
        document.querySelectorAll('form[data-list-filter]').forEach(form => {
            delete form.dataset.listSending;
            form.querySelectorAll('[data-list-empty]').forEach(element => {
                element.disabled = false;
                delete element.dataset.listEmpty;
            });
        });
    });

    const focus = stored();
    store(null);
    if (focus && focus.path === location.pathname) {
        const field = document.getElementById(focus.id);
        if (field && field.closest('form[data-list-filter]')) {
            field.focus();
            if (field.type === 'search' && typeof field.setSelectionRange === 'function') {
                field.setSelectionRange(field.value.length, field.value.length);
            }
        }
    }
})();
