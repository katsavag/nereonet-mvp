// The delivery count's rows (shiporder/parts :: countRow): the cook's one list and the Master's Change. Only an
// enhancement: without this script the form is sent the same and the server says the differences after Save.
//  - The number in "Arrived" (prefilled with the invoice's) and in "The paper invoice says" is selected on focus, so
//    typing replaces it.
//  - Typing a number other than the reference says the difference in words under the input ("2 more than the
//    invoice", "3 fewer than the invoice", "Did not arrive" for 0); the same number again, or none, says nothing.
//  - The row is marked as it stands: .is-different (not what the invoice says), .is-empty (no number), .is-none (0).
//  - "Did not arrive": ticked, the input shows 0 (dimmed); unticked, the number it had is back. Typing 0 ticks it,
//    another number unticks it.
//  - The Save bar says what Save will record ("37 as the invoice · 2 different · 1 without a number").
//  - "Read wrong?": a row whose number is still the invoice's follows the corrected number, so the draft of
//    js/save.js brings it back with the rest after the correction is saved.
//  - "Add a line from the paper invoice": the unit of the item chosen is shown beside the quantity.
// The listeners are on the capture phase, so the draft of js/save.js (a listener on the form) sees the row as this
// script leaves it. A draft put back before this script runs is read from the fields as they are.
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

    const summary = document.querySelector('[data-count-summary]');
    const rows = Array.from(document.querySelectorAll('[data-count-row]')).map(row => ({
        row: row,
        input: row.querySelector('.count-row-controls input[data-quantity]'),
        none: row.querySelector('[data-count-none]'),
        diff: row.querySelector('[data-count-diff]'),
        reference: number(row.dataset.reference),
        base: number(row.dataset.base)
    })).filter(r => r.input && r.none && r.diff);

    function total() {
        if (!summary) {
            return;
        }
        let same = 0;
        let different = 0;
        let empty = 0;
        rows.forEach(r => {
            if (r.row.classList.contains('is-empty')) {
                empty++;
            } else if (r.row.classList.contains('is-different')) {
                different++;
            } else {
                same++;
            }
        });
        const parts = [summary.dataset.same.replace('{n}', same)];
        if (different > 0) {
            parts.push(summary.dataset.different.replace('{n}', different));
        }
        if (empty > 0) {
            parts.push(summary.dataset.empty.replace('{n}', empty));
        }
        summary.textContent = parts.join(' · ');
    }

    // The row as its fields stand: the tick follows the number, then the marks and the words.
    function show(r) {
        const data = r.row.dataset;
        const value = number(r.input.value);
        const blank = r.input.value.trim() === '';
        if (value !== null && value !== 0) {
            r.was = r.input.value;
        }
        r.none.checked = value === 0;
        r.row.classList.toggle('is-none', value === 0);
        r.row.classList.toggle('is-empty', blank);
        r.row.classList.toggle('is-different', value !== null && (r.base === null || value !== r.base));
        if (value === null || value === r.reference) {
            r.diff.textContent = '';
        } else if (value === 0) {
            r.diff.textContent = data.none;
        } else if (r.reference === null) {
            r.diff.textContent = '';
        } else {
            r.diff.textContent = (value < r.reference ? data.less : data.more)
                .replace('{n}', plain(Math.abs(value - r.reference)));
        }
        const need = r.row.querySelector('[data-count-need]');
        if (need) {
            need.hidden = !blank;
        }
    }

    rows.forEach(r => {
        // What the input goes back to when "Did not arrive" is unticked: its last number that was not 0.
        r.was = r.reference ? r.row.dataset.reference : '';
        if (r.none.checked && number(r.input.value) !== 0) {
            r.input.value = '0';    // ticked without this script, or put back by the draft
        }
        show(r);
        r.input.addEventListener('input', () => {
            show(r);
            total();
        }, true);
        r.none.addEventListener('change', () => {
            r.input.value = r.none.checked ? '0' : r.was;
            show(r);
            total();
        }, true);

        const fix = r.row.querySelector('[data-count-fix]');
        const fixInput = fix && fix.querySelector('input[data-quantity]');
        const fixButton = fix && fix.querySelector('button[type="submit"]');
        if (fixInput && fixButton && fixButton.form) {
            fixButton.form.addEventListener('submit', () => {
                const says = number(fixInput.value);
                if (says !== null && r.reference !== null && number(r.input.value) === r.reference) {
                    r.input.value = fixInput.value.trim();
                    r.input.dispatchEvent(new Event('input', {bubbles: true}));
                }
            });
        }
    });
    total();

    // "Add a line from the paper invoice": the unit of the item chosen, beside the quantity.
    const addItem = document.querySelector('[data-count-add-item]');
    const addUnit = document.querySelector('[data-count-add-unit]');
    if (addItem && addUnit) {
        const unit = () => {
            const option = addItem.selectedOptions[0];
            addUnit.textContent = (option && option.dataset.unit) || '';
            addUnit.hidden = addUnit.textContent === '';
        };
        addItem.addEventListener('change', unit);
        unit();
    }
})();
