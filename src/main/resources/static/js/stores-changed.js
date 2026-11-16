// Count the inventory, stores/count.html. Loaded by that page only. Only an enhancement: without it the form is sent
// the same, with no mark on the rows and no number in the bar.
//  - A row whose number is no longer the one the page showed (the hidden shown[id] field) gets .is-changed and the
//    word "changed" (data-count-mark), so the cook sees what "Save count" will send. "12" and "12,0" are the same.
//  - The Save bar says how many rows are changed (data-count-total with its data-none, data-one and data-many texts).
// It follows every input and change of the form, so a draft put back by js/save.js is marked too.
(function () {
    'use strict';

    const form = document.querySelector('form[data-count-form]');
    const total = form && form.querySelector('[data-count-total]');
    if (!total) {
        return;
    }

    function number(text) {
        const typed = text.trim().replace(',', '.');
        return typed !== '' && isFinite(typed) ? Number(typed) : null;
    }

    function update() {
        let changed = 0;
        form.querySelectorAll('[data-count-row]').forEach(row => {
            const input = row.querySelector('input[data-quantity]');
            const shown = row.querySelector('[data-count-shown]');
            const mark = row.querySelector('[data-count-mark]');
            if (!input || !shown || !mark) {
                return;
            }
            const typed = number(input.value);
            const was = number(shown.value);
            const differs = typed === null || was === null ? input.value.trim() !== shown.value.trim() : typed !== was;
            row.classList.toggle('is-changed', differs);
            mark.hidden = !differs;
            if (differs) {
                changed++;
            }
        });
        total.textContent = changed === 0 ? total.dataset.none
            : changed === 1 ? total.dataset.one : total.dataset.many.replace('{count}', changed);
    }

    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
})();
