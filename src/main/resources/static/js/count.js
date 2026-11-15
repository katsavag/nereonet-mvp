// The delivery count's rows (P63, shiporder/parts :: countRow): the cook's count and the Master's Change. Only an
// enhancement: every row is its own form and works without this script (the server confirms the number typed, or the
// reference when nothing is typed).
//  - Typing a number other than the reference turns the button "12 arrived" into "Confirm 23", and says the difference
//    in words under the row ("2 kg less than the invoice"); emptied again, the button is "12 arrived" again. A row
//    without a reference keeps "Confirm" until a number is typed.
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

    document.querySelectorAll('form[data-count-row]').forEach(form => {
        const data = form.dataset;
        const input = form.querySelector('input[name="quantity"]');
        const button = form.querySelector('[data-count-button]');
        const diff = form.querySelector('[data-count-diff]');
        const reference = number(data.reference);

        const update = () => {
            const value = number(input.value);
            if (value === null) {
                button.textContent = data.arrived;
                diff.textContent = '';
                return;
            }
            const shown = input.value.trim().replace(',', '.');
            if (reference !== null && value === reference) {
                button.textContent = data.arrived;
                diff.textContent = '';
                return;
            }
            button.textContent = data.confirm.replace('{n}', shown);
            if (reference === null) {
                diff.textContent = '';
                return;
            }
            const by = Math.abs(value - reference);
            const unit = by === 1 ? data.unitOne : data.unitMany;
            diff.textContent = (value < reference ? data.less : data.more).replace('{n}', plain(by))
                .replace('{unit}', unit || '').replace(/\s+/g, ' ');
        };
        if (input && button && diff) {
            input.addEventListener('input', update);
            update();
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
