// Lists whose rows are acted on (P21, the approved requests list). Only enhancements: without this script the row
// checkboxes and the action buttons work as plain form fields, and each row's main link opens it.
//  - A form with data-selection: its [data-select-all] checkboxes (the table header; on phones, where the header is
//    hidden, the selection line above the table) check or clear every [data-select-row] checkbox of the page, and
//    are half-checked when some are. [data-selection-count] says how many are selected (its data-none, data-one and
//    data-many texts, "{count} selected"); it is a live region. The form's buttons with data-needs-selection,
//    also those outside it (form="..."), are disabled while nothing is selected. [data-selection-only] elements
//    (select-all, the selection line) are hidden until this script runs. The checkboxes may sit outside the form
//    element and belong to it by their form attribute (P43: a list whose rows hold forms of their own, such as Undo),
//    so the form's elements are read through form.elements and change events through the document.
//  - A table row with data-row-href opens that page when clicked anywhere except on a control (a link, a button, a
//    checkbox or the cell around it). Ctrl or Cmd click and middle click open it in a new tab; selecting text does
//    not open it. The row's main link stays the way to it for the keyboard and screen readers.
(function () {
    'use strict';

    const lang = document.documentElement.lang || undefined;

    document.querySelectorAll('form[data-selection]').forEach(form => {
        const elements = () => Array.from(form.elements);
        const rows = () => elements().filter(element => element.matches('input[type=checkbox][data-select-row]'));
        const all = elements().filter(element => element.matches('input[type=checkbox][data-select-all]'));
        const count = form.querySelector('[data-selection-count]')
            || document.querySelector('[data-selection-count][data-selection-for="' + form.id + '"]');
        const needing = elements().filter(element => element.hasAttribute('data-needs-selection'));

        function update() {
            const boxes = rows();
            const selected = boxes.filter(box => box.checked).length;
            all.forEach(box => {
                box.checked = boxes.length > 0 && selected === boxes.length;
                box.indeterminate = selected > 0 && selected < boxes.length;
            });
            if (count) {
                const text = selected === 0 ? count.dataset.none
                    : selected === 1 ? count.dataset.one
                        : count.dataset.many.replace('{count}', selected.toLocaleString(lang));
                if (count.textContent !== text) {
                    count.textContent = text;
                }
            }
            // Not while the form is being sent: js/save.js keeps its buttons disabled then.
            if (!form.dataset.sending) {
                needing.forEach(button => {
                    button.disabled = selected === 0;
                });
            }
        }

        document.querySelectorAll('[data-selection-only]').forEach(element => {
            const owner = element.closest('form[data-selection]') || (element.dataset.selectionFor
                ? document.getElementById(element.dataset.selectionFor) : null);
            if (owner === form || element.querySelector('[form="' + form.id + '"]')) {
                element.hidden = false;
            }
        });
        all.forEach(box => box.addEventListener('change', () => {
            rows().forEach(row => {
                row.checked = box.checked;
            });
            update();
        }));
        document.addEventListener('change', event => {
            if (event.target.matches('[data-select-row]') && event.target.form === form) {
                update();
            }
        });
        // Back to this page from the browser's cache: the checkboxes are as they were, and js/save.js has just given
        // the buttons back.
        window.addEventListener('pageshow', () => setTimeout(update, 0));
        update();
    });

    const CONTROLS = 'a, button, input, select, textarea, label, summary, .cell-select';

    function rowOf(event) {
        const row = event.target.closest('tr[data-row-href]');
        if (!row || event.target.closest(CONTROLS)) {
            return null;
        }
        const selection = window.getSelection && window.getSelection();
        if (selection && !selection.isCollapsed && row.contains(selection.anchorNode)) {
            return null; // the user is selecting text
        }
        return row;
    }

    document.querySelectorAll('tr[data-row-href]').forEach(row => row.classList.add('is-row-link'));

    document.addEventListener('click', event => {
        const row = event.button === 0 && rowOf(event);
        if (!row) {
            return;
        }
        if (event.ctrlKey || event.metaKey) {
            window.open(row.dataset.rowHref, '_blank', 'noopener');
        } else {
            window.location.assign(row.dataset.rowHref);
        }
    });

    document.addEventListener('auxclick', event => {
        const row = event.button === 1 && rowOf(event);
        if (row) {
            window.open(row.dataset.rowHref, '_blank', 'noopener');
        }
    });
})();
