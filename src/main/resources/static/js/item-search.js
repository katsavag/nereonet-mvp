// "Add an item" on the office's order page (P50, radios since P59; templates/officeorder/detail.html). Only an
// enhancement: without this script the items not on the order are plain radios (name="itemId") grouped by category,
// and the form posts as it is.
//  - [data-item-search] (hidden until this script runs) filters the rows ([data-item-option]) of the list it names in
//    aria-controls by name, ignoring case; a category ([data-item-group]) with no match is hidden. A chosen item that
//    no longer matches is unchosen.
//  - [data-item-search-empty] (a live region) says when nothing matches.
//  - Enter in the search field never sends the form: with one match it chooses it and goes to the quantity;
//    Arrow down goes to the list (the chosen item, else the first one shown).
//  - [data-item-summary] (hidden until this script runs, then the quantity's description) says what is being added:
//    "Adding: Basmati rice (kg)" from its data-text and the chosen radio's data-label, or its data-empty text.
//  - [data-item-quantity-label] reads "Quantity (kg)" (its data-text and the radio's data-unit) once an item is chosen.
//  - P60: the quantity field (input[data-quantity]) takes the chosen item's rule (the radio's data-quantity-max and
//    -decimals, and its unit) and js/quantity.js shows its limit in the hint ("Up to 2000 kg"); nothing chosen, the
//    hint says the limit depends on the unit (its data-empty).
(function () {
    'use strict';

    document.querySelectorAll('[data-item-picker]').forEach(picker => {
        const radios = Array.from(picker.querySelectorAll('input[type="radio"][name="itemId"]'));
        const summary = picker.querySelector('[data-item-summary]');
        const quantity = picker.querySelector('input[data-quantity]');
        const quantityHint = picker.querySelector('[data-quantity-hint][data-empty]');
        const quantityLabel = picker.querySelector('[data-item-quantity-label]');
        const plainLabel = quantityLabel ? quantityLabel.textContent : '';

        function chosen() {
            return radios.find(radio => radio.checked && !radio.closest('li').hidden) || null;
        }

        function showChoice() {
            const radio = chosen();
            if (summary) {
                summary.textContent = radio ? summary.dataset.text.replace('{0}', radio.dataset.label)
                    : summary.dataset.empty;
                summary.classList.toggle('is-empty', !radio);
            }
            if (quantityLabel) {
                quantityLabel.textContent = radio ? quantityLabel.dataset.text.replace('{0}', radio.dataset.unit)
                    : plainLabel;
            }
            if (quantity) {
                if (radio) {
                    quantity.dataset.quantityMax = radio.dataset.quantityMax || '';
                    quantity.dataset.quantityDecimals = radio.dataset.quantityDecimals || '0';
                    quantity.dataset.quantityUnit = radio.dataset.unit || '';
                    quantity.setAttribute('inputmode', quantity.dataset.quantityDecimals === '0' ? 'numeric' : 'decimal');
                    quantity.dispatchEvent(new Event('quantity-rule'));
                } else {
                    delete quantity.dataset.quantityMax;
                    quantity.dataset.quantityDecimals = '3';
                    delete quantity.dataset.quantityUnit;
                    if (quantityHint) {
                        quantityHint.textContent = quantityHint.dataset.empty;
                        quantityHint.classList.remove('quantity-hint-error');
                    }
                }
            }
        }

        if (summary) {
            summary.hidden = false;
            if (quantity) {
                quantity.setAttribute('aria-describedby',
                    (summary.id + ' ' + (quantity.getAttribute('aria-describedby') || '')).trim());
            }
        }
        radios.forEach(radio => radio.addEventListener('change', showChoice));
        // The browser may bring back a chosen item (Back, reload).
        showChoice();

        const input = picker.querySelector('[data-item-search]');
        const list = input && document.getElementById(input.getAttribute('aria-controls'));
        if (!input || !list) {
            return;
        }
        const empty = picker.querySelector('[data-item-search-empty]');
        picker.querySelectorAll('[data-item-search-part]').forEach(part => { part.hidden = false; });

        function visible() {
            return radios.filter(radio => !radio.closest('li').hidden);
        }

        function filter() {
            const query = input.value.trim().toLocaleLowerCase();
            list.querySelectorAll('[data-item-group]').forEach(group => {
                let shown = 0;
                group.querySelectorAll('[data-item-option]').forEach(option => {
                    const match = !query || option.textContent.toLocaleLowerCase().includes(query);
                    const row = option.closest('li');
                    row.hidden = !match;
                    const radio = option.querySelector('input');
                    if (!match && radio.checked) {
                        radio.checked = false;
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
            showChoice();
        }

        input.addEventListener('input', filter);
        input.addEventListener('keydown', event => {
            const shown = visible();
            if (event.key === 'Enter') {
                event.preventDefault();
                if (shown.length === 1) {
                    shown[0].checked = true;
                    showChoice();
                    (quantity || shown[0]).focus();
                }
            } else if (event.key === 'ArrowDown' && shown.length > 0) {
                event.preventDefault();
                (shown.find(radio => radio.checked) || shown[0]).focus();
            }
        });
    });
})();
