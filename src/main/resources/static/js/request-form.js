// New request and Edit request (templates/shiprequest/form.html). Only an enhancement: without this script the
// whole list shows and every button posts the form as it is.
//  - [data-request-search] (hidden until this script runs) filters the rows ([data-request-row], by data-name) by
//    name, ignoring case; a group ([data-request-group]) with no match is hidden. What is typed stays in the hidden
//    rows and is sent with the form.
//  - [data-request-search-empty] (a live region) says when nothing matches.
//  - Enter never sends the form from a field: in the search or a quantity it does nothing (the form's first button
//    could be "Add to request"); in the new item's fields it presses "Add to request".
(function () {
    'use strict';

    document.querySelectorAll('[data-request-form]').forEach(form => {
        const input = form.querySelector('[data-request-search]');
        const empty = form.querySelector('[data-request-search-empty]');
        const add = form.querySelector('[data-request-add]');
        const newItem = form.querySelector('[data-request-new-item]');

        if (input) {
            form.querySelectorAll('[data-request-search-part]').forEach(part => { part.hidden = false; });
            input.addEventListener('input', () => {
                const query = input.value.trim().toLocaleLowerCase();
                let shown = 0;
                form.querySelectorAll('[data-request-group]').forEach(group => {
                    let inGroup = 0;
                    group.querySelectorAll('[data-request-row]').forEach(row => {
                        const match = !query || (row.dataset.name || '').includes(query);
                        row.hidden = !match;
                        inGroup += match ? 1 : 0;
                    });
                    group.hidden = inGroup === 0;
                    shown += inGroup;
                });
                if (empty) {
                    empty.textContent = shown === 0 ? empty.dataset.text : '';
                }
            });
        }

        form.addEventListener('keydown', event => {
            const field = event.target;
            if (event.key !== 'Enter' || !(field instanceof HTMLInputElement)) {
                return;
            }
            event.preventDefault();
            if (add && newItem && newItem.contains(field)) {
                add.click();
            }
        });
    });
})();
