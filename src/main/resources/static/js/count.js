// The delivery count's rows (shiporder/parts :: countRow): the cook's count and the Master's Change. Only an
// enhancement: every row is its own form and works without this script (the server confirms the number in the input
// and says the difference after Confirm).
//  - The number in "Arrived" (prefilled with the invoice's) and in "The paper invoice says" is selected on focus, so
//    typing replaces it.
//  - Typing a number other than the reference says the difference in words under the row ("2 more than the invoice",
//    "3 fewer than the invoice", "Did not arrive" for 0); the same number again, or none, says nothing.
//  - "Add a note" opens the row's one-line note in place (without the script it reloads the page with it open).
(function () {
    'use strict';

    function number(text) {
        const trimmed = String(text || '').trim().replace(',', '.');
        if (trimmed === '' || trimmed === '.') {
            return null;
        }
        const value = Number(trimmed);
        return Number.isNaN(value) ? null : value;
    }

    // 2.5 as typed, never 2.4999999.
    function plain(value) {
        return String(Math.round(value * 1000) / 1000);
    }

    document.querySelectorAll('.count-row-form input[data-quantity]').forEach(input => {
        input.addEventListener('focus', () => input.select());
    });

    document.querySelectorAll('form[data-count-row]').forEach(form => {
        const data = form.dataset;
        const input = form.querySelector('input[name="quantity"]');
        const diff = form.querySelector('[data-count-diff]');
        const reference = number(data.reference);

        const update = () => {
            const value = number(input.value);
            if (value === null || value === reference) {
                diff.textContent = '';
            } else if (value === 0) {
                diff.textContent = data.none;
            } else if (reference === null) {
                diff.textContent = '';
            } else {
                diff.textContent = (value < reference ? data.less : data.more)
                    .replace('{n}', plain(Math.abs(value - reference)));
            }
        };
        if (input && diff) {
            input.addEventListener('input', update);
        }

        const open = form.querySelector('[data-count-note-open]');
        const note = form.querySelector('[data-count-note]');
        if (open && note) {
            open.addEventListener('click', event => {
                event.preventDefault();
                note.hidden = false;
                open.hidden = true;
                const field = note.querySelector('input');
                if (field) {
                    field.focus();
                }
            });
        }
    });
})();
