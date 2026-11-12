// The Selected ships list of the announcement form (P28, templates/announcement/form.html). Only enhancements: without
// this script every active ship is listed with its checkbox, the radios choose the target, and the server ignores the
// ticked ships for All ships.
//  - [data-ship-picker] is shown only while its form's "ships" radio (aria-controls) is checked.
//  - [data-ship-filter="type" | "flag"] selects show only the ships (li with data-type / data-flag) that match;
//    [data-ship-picker-empty] says when none does. A ticked ship hidden by a filter stays ticked.
//  - [data-ship-select-all] ticks the ships shown; [data-ship-clear] unticks every ship, shown or not.
//  - [data-ship-picker-count] (a live region) says "2 of 5 selected", and how many of those the filters hide
//    (the picker's data-count and data-count-hidden texts).
//  - [data-ship-picker-tools] (filters, count and buttons) is hidden until this script runs.
(function () {
    'use strict';

    const lang = document.documentElement.lang || undefined;

    document.querySelectorAll('[data-ship-picker]').forEach(picker => {
        const form = picker.closest('form');
        const toggle = form && form.querySelector('input[type=radio][aria-controls="' + picker.id + '"]');
        const radios = toggle ? Array.from(form.querySelectorAll('input[type=radio][name="' + toggle.name + '"]')) : [];
        const items = Array.from(picker.querySelectorAll('.ship-picker-list > li'));
        const boxes = () => items.map(item => item.querySelector('input[type=checkbox]')).filter(Boolean);
        const filters = Array.from(picker.querySelectorAll('[data-ship-filter]'));
        const tools = picker.querySelector('[data-ship-picker-tools]');
        const count = picker.querySelector('[data-ship-picker-count]');
        const empty = picker.querySelector('[data-ship-picker-empty]');

        function shown(item) {
            return !item.hidden;
        }

        function updateCount() {
            if (!count) {
                return;
            }
            const all = boxes();
            const selected = all.filter(box => box.checked);
            const hidden = selected.filter(box => !shown(box.closest('li'))).length;
            const template = hidden > 0 ? picker.dataset.countHidden : picker.dataset.count;
            const text = template.replace('{count}', selected.length.toLocaleString(lang))
                .replace('{total}', all.length.toLocaleString(lang))
                .replace('{hidden}', hidden.toLocaleString(lang));
            if (count.textContent !== text) {
                count.textContent = text;
            }
        }

        function applyFilters() {
            let visible = 0;
            items.forEach(item => {
                const match = filters.every(filter => !filter.value
                    || (item.dataset[filter.dataset.shipFilter] || '') === filter.value);
                item.hidden = !match;
                if (match) {
                    visible++;
                }
            });
            if (empty) {
                empty.hidden = visible > 0;
            }
            updateCount();
        }

        function updateVisibility() {
            if (toggle) {
                picker.hidden = !toggle.checked;
            }
        }

        if (tools) {
            tools.hidden = false;
        }
        filters.forEach(filter => filter.addEventListener('change', applyFilters));
        picker.addEventListener('change', event => {
            if (event.target.type === 'checkbox') {
                updateCount();
            }
        });
        const selectAll = picker.querySelector('[data-ship-select-all]');
        if (selectAll) {
            selectAll.addEventListener('click', () => {
                items.filter(shown).forEach(item => {
                    const box = item.querySelector('input[type=checkbox]');
                    if (box) {
                        box.checked = true;
                    }
                });
                updateCount();
            });
        }
        const clear = picker.querySelector('[data-ship-clear]');
        if (clear) {
            clear.addEventListener('click', () => {
                boxes().forEach(box => {
                    box.checked = false;
                });
                updateCount();
            });
        }
        radios.forEach(radio => radio.addEventListener('change', updateVisibility));
        // The browser may restore the radios and boxes after a reload or Back: read them once the page is shown.
        window.addEventListener('pageshow', () => {
            updateVisibility();
            applyFilters();
        });
        updateVisibility();
        applyFilters();
    });
})();
