// "Add an item" on the office's order page (P50, templates/officeorder/detail.html). Only an enhancement: without this
// script the vessel's items are a list box grouped by category, and the form posts as it is.
//  - [data-item-search] (hidden until this script runs) filters the options of the select it names in aria-controls
//    by name, ignoring case; a category with no match is hidden. A chosen item that no longer matches is unchosen.
//  - [data-item-search-empty] (a live region) says when nothing matches.
//  - Enter in the search field never sends the form: with one match it chooses it and goes to the quantity;
//    Arrow down goes to the list.
(function () {
    'use strict';

    document.querySelectorAll('[data-item-search]').forEach(input => {
        const select = document.getElementById(input.getAttribute('aria-controls'));
        const picker = input.closest('[data-item-picker]');
        if (!select || !picker) {
            return;
        }
        const empty = picker.querySelector('[data-item-search-empty]');
        const quantity = picker.querySelector('[data-item-quantity]');
        picker.querySelectorAll('[data-item-search-part]').forEach(part => { part.hidden = false; });

        function visible() {
            return Array.from(select.options).filter(option => !option.hidden);
        }

        function filter() {
            const query = input.value.trim().toLocaleLowerCase();
            select.querySelectorAll('optgroup').forEach(group => {
                let shown = 0;
                group.querySelectorAll('option').forEach(option => {
                    const match = !query || option.textContent.toLocaleLowerCase().includes(query);
                    option.hidden = !match;
                    if (!match && option.selected) {
                        option.selected = false;
                    }
                    shown += match ? 1 : 0;
                });
                group.hidden = shown === 0;
            });
            if (empty) {
                const none = visible().length === 0;
                empty.hidden = !none;
                empty.textContent = none ? empty.dataset.text : '';
            }
        }

        input.addEventListener('input', filter);
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                const shown = visible();
                if (shown.length === 1) {
                    shown[0].selected = true;
                    (quantity || select).focus();
                }
            } else if (event.key === 'ArrowDown' && visible().length > 0) {
                event.preventDefault();
                select.focus();
            }
        });
    });
})();
