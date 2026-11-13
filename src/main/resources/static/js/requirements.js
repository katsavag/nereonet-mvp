// Required documents (P43): requirement/grid.html. Only enhancements: without this script the row toggle is submitted
// and applied by the server, and the filter is a select with Show.
//  - Row toggle: in a [data-requirement-row], the [data-row-all] checkbox ("Select all ranks for Passport") ticks or
//    clears every [data-row-rank] checkbox of the row; it shows the mixed state while only some are ticked (then it
//    is unchecked, so the server does not apply it over the ranks sent).
//  - The combined filter ([data-requirement-filter]): the select of vessel types and vessels becomes a combobox
//    (the ARIA combobox pattern with a listbox: typing filters the options, Down / Up move, Enter picks, Esc closes,
//    a click picks). Each option shows its kind, "Tanker · vessel type", "ALDER · Bulk carrier" (from the option's
//    data-kind). Picking sets the select and sends the form, as js/list-filter.js does for a changed filter.
(function () {
    'use strict';

    // Row toggles
    document.querySelectorAll('[data-requirement-row]').forEach(row => {
        const all = row.querySelector('input[data-row-all]');
        const ranks = Array.from(row.querySelectorAll('input[data-row-rank]'));
        if (!all) {
            return;
        }
        if (ranks.length === 0) {
            all.disabled = true; // every rank already required on all vessels
            return;
        }
        const update = () => {
            const ticked = ranks.filter(box => box.checked).length;
            all.checked = ticked === ranks.length;
            all.indeterminate = ticked > 0 && ticked < ranks.length;
        };
        all.addEventListener('change', () => {
            ranks.forEach(box => {
                box.checked = all.checked;
            });
            all.indeterminate = false;
        });
        ranks.forEach(box => box.addEventListener('change', update));
        update();
    });

    // The combined filter
    const field = document.querySelector('[data-requirement-filter]');
    const select = field && field.querySelector('select');
    const form = select && select.form;
    if (!select || !form) {
        return;
    }

    const options = Array.from(select.options).map((option, index) => ({
        value: option.value,
        label: option.textContent.trim(),
        kind: option.dataset.kind || '',
        text: (option.textContent.trim() + ' · ' + (option.dataset.kind || '')).trim(),
        id: 'vesselOption' + index
    }));
    const current = options.find(option => option.value === select.value) || options[0];

    const input = document.createElement('input');
    input.type = 'text';
    input.id = select.id;
    input.className = 'form-control';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-controls', 'vesselOptions');
    input.setAttribute('aria-describedby', select.getAttribute('aria-describedby') || '');
    input.value = current ? current.text : '';
    input.placeholder = field.dataset.searchLabel || '';

    const list = document.createElement('ul');
    list.id = 'vesselOptions';
    list.className = 'combobox-list';
    list.setAttribute('role', 'listbox');
    list.hidden = true;

    const wrapper = document.createElement('div');
    wrapper.className = 'combobox';
    select.removeAttribute('id');
    select.hidden = true;
    select.insertAdjacentElement('beforebegin', wrapper);
    wrapper.append(input, list);

    let shown = [];
    let active = -1;

    function render(filter) {
        const needle = (filter || '').trim().toLowerCase();
        shown = options.filter(option => !needle || option.text.toLowerCase().includes(needle));
        list.textContent = '';
        if (shown.length === 0) {
            const none = document.createElement('li');
            none.className = 'combobox-none';
            none.textContent = field.dataset.noResults || '';
            list.append(none);
        }
        shown.forEach(option => {
            const item = document.createElement('li');
            item.id = option.id;
            item.className = 'combobox-option';
            item.setAttribute('role', 'option');
            item.setAttribute('aria-selected', option.value === select.value ? 'true' : 'false');
            item.dataset.value = option.value;
            const name = document.createElement('span');
            name.className = 'combobox-option-label';
            name.textContent = option.label;
            const kind = document.createElement('span');
            kind.className = 'combobox-option-kind';
            kind.textContent = option.kind ? ' · ' + option.kind : '';
            item.append(name, kind);
            list.append(item);
        });
        setActive(-1);
    }

    function setActive(index) {
        active = index;
        Array.from(list.querySelectorAll('[role=option]')).forEach((item, i) => {
            item.classList.toggle('is-active', i === index);
        });
        if (index >= 0 && shown[index]) {
            input.setAttribute('aria-activedescendant', shown[index].id);
            const item = document.getElementById(shown[index].id);
            if (item && item.scrollIntoView) {
                item.scrollIntoView({block: 'nearest'});
            }
        } else {
            input.removeAttribute('aria-activedescendant');
        }
    }

    function open(filter) {
        render(filter);
        list.hidden = false;
        input.setAttribute('aria-expanded', 'true');
    }

    function close() {
        list.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        setActive(-1);
    }

    function pick(option) {
        if (!option) {
            return;
        }
        input.value = option.text;
        close();
        if (option.value !== select.value) {
            select.value = option.value;
            // The same way as a changed filter (js/list-filter.js): the form sends itself.
            select.dispatchEvent(new Event('change', {bubbles: true}));
            if (!form.dataset.listSending) {
                form.submit();
            }
        }
    }

    input.addEventListener('input', () => open(input.value));
    input.addEventListener('focus', () => open(''));
    input.addEventListener('click', () => {
        if (list.hidden) {
            open('');
        }
    });
    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            if (list.hidden) {
                open(input.value);
            }
            setActive(shown.length === 0 ? -1 : (active + 1) % shown.length);
        } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            if (list.hidden) {
                open(input.value);
            }
            setActive(shown.length === 0 ? -1 : (active <= 0 ? shown.length - 1 : active - 1));
        } else if (event.key === 'Enter') {
            event.preventDefault();
            if (!list.hidden && active >= 0) {
                pick(shown[active]);
            } else if (!list.hidden && shown.length === 1) {
                pick(shown[0]);
            }
        } else if (event.key === 'Escape') {
            if (!list.hidden) {
                event.preventDefault();
                close();
                input.value = current ? current.text : '';
            }
        } else if (event.key === 'Tab') {
            close();
        }
    });
    list.addEventListener('mousedown', event => {
        const item = event.target.closest('[role=option]');
        if (item) {
            event.preventDefault();
            pick(shown.find(option => option.value === item.dataset.value));
        }
    });
    document.addEventListener('click', event => {
        if (!wrapper.contains(event.target)) {
            close();
        }
    });
    // The combobox submits the form itself; Enter in the field must not send the select's old value.
    form.addEventListener('submit', event => {
        if (document.activeElement === input && !list.hidden) {
            event.preventDefault();
        }
    });
})();
