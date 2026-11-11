// Form helpers (P05). Only enhancements: without this script every form still shows its limits and help text, the
// error summary still links to the fields, and the forms submit as usual.
//  - [data-counter-for]: the counter under a field with a character limit, "123 / 4,000" while typing; the page
//    shows "Up to 4,000 characters" until this runs. Texts come from data-template / data-over-template, where
//    {count}, {max} and {over} are replaced.
//  - .error-summary: gets focus when the page loads (in a modal: when the modal has opened); its links focus the
//    field and scroll its label into view, and start with the field's label ("Title: Too long.").
//  - input[type=file][data-file-info]: the chosen file's name and size, and a warning when it is larger than
//    data-max-size (bytes; text in data-too-large).
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
})();
