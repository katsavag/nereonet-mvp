// Form helpers (P05). Only enhancements: without this script every form still shows its limits and help text, the
// error summary still links to the fields, and the forms submit as usual.
//  - [data-counter-for]: the counter under a field with a character limit, "123 / 4,000" while typing; the page
//    shows "Up to 4,000 characters" until this runs. Texts come from data-template / data-over-template, where
//    {count}, {max} and {over} are replaced.
//  - .error-summary: gets focus when the page loads (in a modal: when the modal has opened); its links focus the
//    field and scroll its label into view, and start with the field's label ("Title: Too long.").
//  - input[type=file][data-file-info]: the chosen file's name and size, and a warning when it is larger than
//    data-max-size (bytes; text in data-too-large).
//  - [data-shown-for]: a field shown only while the select with that id has the value data-shown-value (the item
//    form's Place, for Provisions). Without this script the field stays visible.
//  - input[data-input-filter]: digits or letters only, as typed or pasted (P46, see below).
(function () {
    'use strict';

    const lang = document.documentElement.lang || undefined;
    const integer = new Intl.NumberFormat(lang);
    const decimal = new Intl.NumberFormat(lang, {maximumFractionDigits: 1});

    function fill(template, values) {
        return template.replace(/\{(\w+)\}/g, (match, key) => key in values ? values[key] : match);
    }

    function labelText(field) {
        const label = field.id && document.querySelector('label[for="' + CSS.escape(field.id) + '"]');
        if (!label) {
            return '';
        }
        const copy = label.cloneNode(true);
        copy.querySelectorAll('.form-label-optional').forEach(optional => optional.remove());
        return copy.textContent.trim();
    }

    // Character counters
    document.querySelectorAll('[data-counter-for]').forEach(counter => {
        const field = document.getElementById(counter.dataset.counterFor);
        const max = Number(counter.dataset.max);
        if (!field || !max) {
            return;
        }
        const update = () => {
            const count = field.value.length;
            const over = count - max;
            counter.textContent = fill(over > 0 ? counter.dataset.overTemplate : counter.dataset.template,
                {count: integer.format(count), max: integer.format(max), over: integer.format(over)});
            counter.classList.toggle('is-over', over > 0);
        };
        field.addEventListener('input', update);
        update();
    });

    // Error summary
    document.querySelectorAll('.error-summary').forEach((summary, index) => {
        summary.querySelectorAll('a[href^="#"]').forEach(link => {
            const field = document.getElementById(decodeURIComponent(link.hash.slice(1)));
            if (!field) {
                return;
            }
            const label = labelText(field);
            if (label) {
                link.textContent = label + ': ' + link.textContent;
            }
            link.addEventListener('click', event => {
                event.preventDefault();
                const target = document.querySelector('label[for="' + CSS.escape(field.id) + '"]') || field;
                target.scrollIntoView({block: 'center'});
                field.focus({preventScroll: true});
            });
        });
        if (index > 0) {
            return; // one form with errors per page; focus the first summary only
        }
        const modal = summary.closest('.modal');
        if (modal) {
            modal.addEventListener('shown.bs.modal', () => summary.focus(), {once: true});
        } else {
            summary.focus();
        }
    });

    // File inputs: name and size of the chosen file
    function size(bytes) {
        if (bytes >= 1024 * 1024) {
            return decimal.format(bytes / (1024 * 1024)) + ' MB';
        }
        if (bytes >= 1024) {
            return decimal.format(bytes / 1024) + ' KB';
        }
        return integer.format(bytes) + ' B';
    }

    document.querySelectorAll('input[type=file][data-file-info]').forEach(input => {
        const info = document.getElementById(input.dataset.fileInfo);
        const max = Number(input.dataset.maxSize) || 0;
        if (!info) {
            return;
        }
        const update = () => {
            const file = input.files && input.files[0];
            const tooLarge = Boolean(file && max && file.size > max);
            info.hidden = !file;
            info.textContent = file
                ? file.name + ' · ' + size(file.size) + (tooLarge ? ' · ' + input.dataset.tooLarge : '')
                : '';
            info.classList.toggle('is-over', tooLarge);
        };
        input.addEventListener('change', update);
        update(); // a file kept by the browser after going back
    });

    // Rank pickers (P18): the access line under the picker (crew/parts :: accessHint) follows the chosen rank. Each
    // option names its role (data-role); the line holds the text for each role (data-access-<role>, lower case) and
    // data-access-none for no rank. Without this script the line shows the saved rank's access.
    function showAccess(select, hint) {
        const option = select.selectedOptions[0];
        const role = option && option.dataset.role;
        const text = hint.getAttribute(role ? 'data-access-' + role.toLowerCase() : 'data-access-none');
        if (text !== null && hint.textContent !== text) {
            hint.textContent = text;
        }
    }

    document.querySelectorAll('select[data-access-hint]').forEach(select => {
        const hint = document.getElementById(select.dataset.accessHint);
        if (!hint) {
            return;
        }
        select.addEventListener('change', () => showAccess(select, hint));
        showAccess(select, hint); // a rank kept by the browser after going back
    });

    // P46: input[data-input-filter="digits" | "letters"]: only those characters are kept as they are typed or pasted
    // (a pasted "IMO 9328716" becomes "9328716"; letters are upper case), the caret staying where the person was
    // typing. A paste is filtered before the field's maxlength applies, so it is not cut short by the dropped text.
    // data-country-name: the id of the element showing the country name of the code (the label of its option in the
    // field's datalist), empty while it is not a known code. Without this script the pattern and the server still check.
    // Delegated, so it also works in the pop-ups.
    const FILTERS = {
        digits: text => text.replace(/[^0-9]/g, ''),
        letters: text => text.replace(/[^A-Za-z]/g, '').toUpperCase()
    };

    function filterOf(target) {
        return target instanceof HTMLInputElement && FILTERS[target.dataset.inputFilter];
    }

    function showCountryName(input) {
        const target = input.dataset.countryName && document.getElementById(input.dataset.countryName);
        if (!target) {
            return;
        }
        const option = input.list && Array.from(input.list.options).find(o => o.value === input.value);
        const name = option ? option.label : '';
        if (target.textContent !== name) {
            target.textContent = name;
        }
    }

    document.addEventListener('input', event => {
        const input = event.target;
        const filter = filterOf(input);
        if (!filter || event.isComposing) {
            return;
        }
        const value = input.value;
        const kept = filter(value);
        if (kept !== value) {
            const caret = input.selectionStart ?? value.length;
            const before = filter(value.slice(0, caret)).length;
            input.value = kept;
            input.setSelectionRange(before, before);
        }
        showCountryName(input);
    });

    document.addEventListener('paste', event => {
        const input = event.target;
        const filter = filterOf(input);
        if (!filter || !event.clipboardData) {
            return;
        }
        event.preventDefault();
        const start = input.selectionStart ?? input.value.length;
        const end = input.selectionEnd ?? start;
        const room = input.maxLength > 0 ? input.maxLength - (input.value.length - (end - start)) : Infinity;
        const text = filter(event.clipboardData.getData('text')).slice(0, Math.max(room, 0));
        input.setRangeText(text, start, end, 'end');
        input.dispatchEvent(new Event('input', {bubbles: true}));
    });

    document.addEventListener('compositionend', event => {
        if (filterOf(event.target)) {
            event.target.dispatchEvent(new Event('input', {bubbles: true}));
        }
    });

    document.querySelectorAll('input[data-country-name]').forEach(showCountryName); // a value kept after going back

    document.querySelectorAll('[data-shown-for]').forEach(field => {
        const select = document.getElementById(field.dataset.shownFor);
        if (!select) {
            return;
        }
        const update = () => { field.hidden = select.value !== field.dataset.shownValue; };
        select.addEventListener('change', update);
        update(); // a value kept by the browser after going back
    });
})();
