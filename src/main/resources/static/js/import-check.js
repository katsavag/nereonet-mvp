// Import from a file, Review step (P39; crewimport/review.html, item/import-review.html): each cell checks itself as
// it is corrected, and the error count and the import button follow, so the person can continue without "Check
// again". Only help: the server checks every row again on submit, and without this script the page works as before
// (the server's errors, Check again).
//  - Cells: input[data-required], input[data-max-length], input[data-email], select[data-required] in the form of
//    [data-import-check] (layout/parts :: importTextCell and the crew review's selects). Their rules, as the server's:
//    data-required (the message when empty), data-max-length with data-too-long, data-email (an address as Bean
//    Validation's @Email accepts it). data-server-error: what the server found; shown again while the value is the
//    one the server checked (e.g. "This email address already has an account"), since only the server knows it.
//  - Typing: after 400 ms without a key the cell is checked; a cell that is now right loses its error at once, one
//    that is wrong only shows it when the person leaves the cell (blur), not while still typing.
//  - Counts: [data-import-check] has data-error-count and data-changing-count for all rows, including those a filter
//    hides; the rows on the page are counted live (an excluded row counts for nothing). The "With errors" count, the
//    "Exclude all N rows with errors" button (data-label-one, data-label-many: its text for 1 and for 100 rows), the
//    import button ([data-import-submit]: enabled with no error and something to import) and its hint (#submitHint)
//    follow.
(function () {
    'use strict';

    // Bean Validation's @Email (Hibernate Validator): a local part of atoms separated by dots, and a domain of labels
    // (letters, digits, hyphens, not at either end) or an IP address in brackets. Empty is checked as required.
    const ATOM = "[a-z0-9!#$%&'*+/=?^_`{|}~\\u0080-\\uFFFF-]+";
    const LOCAL = new RegExp('^' + ATOM + '(\\.' + ATOM + ')*$', 'i');
    const LABEL = '[a-z0-9\\u0080-\\uFFFF]([a-z0-9\\u0080-\\uFFFF-]{0,61}[a-z0-9\\u0080-\\uFFFF])?';
    const DOMAIN = new RegExp('^(' + LABEL + '(\\.' + LABEL + ')*|\\[[0-9a-f:.]+\\])$', 'i');
    const DELAY = 400;

    const isEmail = value => {
        const at = value.lastIndexOf('@');
        if (at < 1 || at === value.length - 1) {
            return false;
        }
        const local = value.slice(0, at);
        const domain = value.slice(at + 1);
        return local.length <= 64 && domain.length <= 255 && LOCAL.test(local) && DOMAIN.test(domain);
    };

    // The cell's error by its own rules, or null.
    const localError = cell => {
        const value = cell.value.trim();
        const data = cell.dataset;
        if (value === '') {
            return data.required || null;
        }
        if (data.maxLength && value.length > Number(data.maxLength)) {
            return data.tooLong || null;
        }
        if (data.email && !isEmail(value)) {
            return data.email;
        }
        return null;
    };

    // Whether the cell still has the value the server checked.
    const unchanged = cell => {
        if (cell.tagName === 'SELECT') {
            return Array.from(cell.options).every(option => option.selected === option.defaultSelected);
        }
        return cell.value === cell.defaultValue;
    };

    const errorOf = cell => localError(cell) || (unchanged(cell) && cell.dataset.serverError) || null;

    const feedbackOf = cell => {
        let feedback = cell.parentElement.querySelector('.invalid-feedback');
        if (!feedback) {
            feedback = document.createElement('div');
            feedback.className = 'invalid-feedback';
            cell.insertAdjacentElement('afterend', feedback);
        }
        return feedback;
    };

    // Shows message on the cell, or (null) clears it.
    const show = (cell, message) => {
        cell.classList.toggle('is-invalid', Boolean(message));
        if (message) {
            cell.setAttribute('aria-invalid', 'true');
            feedbackOf(cell).textContent = message;
        } else {
            cell.removeAttribute('aria-invalid');
            const feedback = cell.parentElement.querySelector('.invalid-feedback');
            if (feedback) {
                feedback.textContent = '';
            }
        }
    };

    document.querySelectorAll('[data-import-check]').forEach(summary => {
        const form = summary.closest('form');
        if (!form) {
            return;
        }
        const rows = Array.from(form.querySelectorAll('tbody tr')).filter(row => row.querySelector('.import-col-row'));
        const cellsOf = row => Array.from(row.querySelectorAll('[data-required], [data-max-length], [data-email]'));
        const isExcluded = row => {
            const box = row.querySelector('.import-exclude input[type="checkbox"]');
            return Boolean(box && box.checked);
        };
        const hasError = row => cellsOf(row).some(cell => cell.classList.contains('is-invalid'));
        const isChanging = row => row.querySelector('.import-col-row').dataset.action !== 'UNCHANGED'
            || cellsOf(row).some(cell => !unchanged(cell));

        // The rows a filter hides keep the counts the server gave.
        const shownErrors = rows.filter(row => !isExcluded(row) && hasError(row)).length;
        const shownChanging = rows.filter(row => !isExcluded(row) && isChanging(row)).length;
        const hiddenErrors = Math.max(0, Number(summary.dataset.errorCount || 0) - shownErrors);
        const hiddenChanging = Math.max(0, Number(summary.dataset.changingCount || 0) - shownChanging);

        const errorCount = summary.querySelector('button[value="errors"] .import-filter-count');
        const excludeErrors = summary.querySelector('.import-exclude-errors');
        const submit = form.querySelector('[data-import-submit]');
        const hint = form.querySelector('#submitHint');

        const update = () => {
            const included = rows.filter(row => !isExcluded(row));
            const errors = hiddenErrors + included.filter(hasError).length;
            const changing = hiddenChanging + included.filter(isChanging).length;
            if (errorCount) {
                errorCount.textContent = String(errors);
                errorCount.classList.toggle('has-errors', errors > 0);
            }
            if (excludeErrors) {
                excludeErrors.hidden = errors === 0;
                const label = errors === 1 ? excludeErrors.dataset.labelOne : excludeErrors.dataset.labelMany;
                if (label && errors > 0) {
                    excludeErrors.textContent = errors === 1 ? label : label.replace('100', String(errors));
                }
            }
            const ready = errors === 0 && changing > 0;
            if (submit) {
                submit.disabled = !ready;
                if (ready) {
                    submit.removeAttribute('aria-describedby');
                } else if (hint) {
                    submit.setAttribute('aria-describedby', hint.id);
                }
            }
            if (hint) {
                hint.hidden = ready;
            }
        };

        rows.forEach(row => {
            cellsOf(row).forEach(cell => {
                let timer = null;
                const check = leaving => {
                    window.clearTimeout(timer);
                    const message = errorOf(cell);
                    // While typing, a new error waits for the person to leave the cell.
                    if (!message || leaving || cell.classList.contains('is-invalid')) {
                        show(cell, isExcluded(row) ? null : message);
                    }
                    update();
                };
                cell.addEventListener('input', () => {
                    window.clearTimeout(timer);
                    timer = window.setTimeout(() => check(false), DELAY);
                });
                cell.addEventListener('change', () => check(cell.tagName === 'SELECT'));
                cell.addEventListener('blur', () => check(true));
            });
            // Excluding a row takes its errors out of the count; including it again checks its cells.
            const box = row.querySelector('.import-exclude input[type="checkbox"]');
            if (box) {
                box.addEventListener('change', () => {
                    cellsOf(row).forEach(cell => show(cell, box.checked ? null : errorOf(cell)));
                    update();
                });
            }
        });
    });
})();
