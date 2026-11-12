// Import from a file, Map columns step (P24, templates/importfile/map.html). Only an enhancement: without this script
// the preview's "To: ..." labels and the "Not imported" list show the mapping the page opened with, and Continue
// checks the mapping as chosen.
//  - form[data-import-map]: when a select[data-import-column] changes, each preview column header
//    ([data-column-target] = the column's index) says where its column goes now (the first select that chose it, its
//    data-target: "To: Email"), or the form's data-target-none ("Not imported"); [data-import-ignored] lists the
//    columns no select uses that have a name or a value (data-column-has-data), by data-column-label ("D · Nationality"),
//    or the form's data-ignored-none.
(function () {
    'use strict';

    document.querySelectorAll('form[data-import-map]').forEach(form => {
        const selects = Array.from(form.querySelectorAll('select[data-import-column]'));
        const targets = Array.from(form.querySelectorAll('[data-column-target]'));
        const ignored = form.querySelector('[data-import-ignored]');

        const update = () => {
            const used = new Map();
            selects.forEach(select => {
                if (select.value !== '' && !used.has(select.value)) {
                    used.set(select.value, select.dataset.target || '');
                }
            });
            targets.forEach(target => {
                const text = used.get(target.dataset.columnTarget);
                target.textContent = text || form.dataset.targetNone || '';
                target.classList.toggle('is-ignored', !text);
            });
            if (ignored) {
                const names = targets
                    .filter(target => !used.has(target.dataset.columnTarget) && target.dataset.columnHasData === 'true')
                    .map(target => target.dataset.columnLabel);
                ignored.textContent = names.length ? names.join(', ') : (form.dataset.ignoredNone || '');
            }
        };

        selects.forEach(select => select.addEventListener('change', update));
    });
})();
