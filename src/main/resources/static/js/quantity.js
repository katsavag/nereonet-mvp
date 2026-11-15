// Quantity fields (P60, layout/parts :: quantityInput, quantityHint, quantityTexts). Only an enhancement: the server
// checks every quantity (core/form/QuantityRules) and every form posts without this script.
//  - input[data-quantity]: what is typed or pasted keeps only digits and one decimal separator; a comma becomes a dot
//    (Greek keyboards). Letters, spaces and signs are dropped as they are typed.
//  - The limit is checked as the person types: too many decimals ("Use whole numbers for pcs.") or above
//    data-quantity-max ("Up to 2000 kg per line. ...") marks the field invalid and shows the message in its hint
//    ([data-quantity-hint] named in aria-describedby); fine again, the hint shows the limit. data-quantity-kept: the
//    value already saved, never flagged.
//  - The rule can change on the page: set data-quantity-max / -decimals / -unit and dispatch "quantity-rule" on the
//    input (js/item-search.js, Add an item). data-quantity-unit-field="newUnit": the unit is typed in that field, and
//    the rule comes from the units JSON of [data-quantity-texts] (unknown units: countable).
(function () {
    'use strict';

    const texts = document.querySelector('[data-quantity-texts]');
    const t = texts ? texts.dataset : {};
    let units = null;
    try {
        units = t.units ? JSON.parse(t.units) : null;
    } catch (e) {
        units = null;
    }

    function fill(template, values) {
        return (template || '').replace(/\{(\w+)\}/g, (all, key) => (key in values ? values[key] : all))
            .replace(/\s+/g, ' ').replace(/\s+([.,])/g, '$1').trim();
    }

    function hintOf(input) {
        const ids = (input.getAttribute('aria-describedby') || '').split(/\s+/);
        for (const id of ids) {
            const el = id && document.getElementById(id);
            if (el && el.hasAttribute('data-quantity-hint')) {
                return el;
            }
        }
        return null;
    }

    function values(input) {
        return {
            max: input.dataset.quantityMax || '',
            unit: input.dataset.quantityUnit || t.unitNone || '',
            decimals: input.dataset.quantityDecimals || '0'
        };
    }

    function limitText(input) {
        const v = values(input);
        return fill(v.decimals === '0' ? t.hintWhole : t.hintMax, v);
    }

    // Keeps digits and the first separator; a comma becomes a dot. Keeps the caret where it was.
    function clean(input) {
        const before = input.value;
        let seen = false;
        let removedBeforeCaret = 0;
        const caret = input.selectionStart;
        let out = '';
        for (let i = 0; i < before.length; i++) {
            let c = before[i] === ',' ? '.' : before[i];
            const keep = (c >= '0' && c <= '9') || (c === '.' && !seen);
            if (c === '.' && keep) {
                seen = true;
            }
            if (keep) {
                out += c;
            } else if (caret !== null && i < caret) {
                removedBeforeCaret++;
            }
        }
        if (out !== before) {
            input.value = out;
            if (caret !== null && document.activeElement === input) {
                const at = caret - removedBeforeCaret;
                try {
                    input.setSelectionRange(at, at);
                } catch (e) {
                    // Some input types do not have a selection.
                }
            }
        }
    }

    // The live check: null when fine (or empty: the server says what is missing), else the message.
    function problem(input) {
        const value = input.value;
        if (!value || value === '.') {
            return null;
        }
        const v = values(input);
        const decimals = Number(v.decimals) || 0;
        const fraction = value.includes('.') ? value.split('.')[1].replace(/0+$/, '') : '';
        if (fraction.length > decimals) {
            return decimals === 0 ? fill(t.errorWhole, v) : fill(t.errorDecimals, v);
        }
        const number = Number(value);
        if (number === 0 && input.dataset.quantityZero !== 'true') {
            return t.errorAboveZero || null;
        }
        const kept = input.dataset.quantityKept;
        if (v.max && number > Number(v.max) && !(kept && Number(kept) === number)) {
            return fill(t.errorMax, v);
        }
        return null;
    }

    function check(input) {
        const hint = hintOf(input);
        const message = problem(input);
        input.classList.toggle('is-invalid', message !== null || input.dataset.quantityServerError === 'true');
        if (message !== null) {
            input.setAttribute('aria-invalid', 'true');
        } else if (input.dataset.quantityServerError !== 'true') {
            input.removeAttribute('aria-invalid');
        }
        // While the server's error is shown under the field (nothing typed since), the hint keeps the limit: the
        // same message twice would be read twice.
        const live = message !== null && input.dataset.quantityServerError !== 'true';
        if (hint) {
            hint.textContent = live ? message : limitText(input);
            hint.classList.toggle('quantity-hint-error', live);
        }
    }

    function applyRule(input, decimals, max, unit) {
        input.dataset.quantityDecimals = String(decimals);
        input.dataset.quantityMax = max;
        if (unit) {
            input.dataset.quantityUnit = unit;
        } else {
            delete input.dataset.quantityUnit;
        }
        input.setAttribute('inputmode', String(decimals) === '0' ? 'numeric' : 'decimal');
        check(input);
    }

    function unitRule(unit) {
        if (!units) {
            return null;
        }
        const key = (unit || '').trim().toLowerCase().replace(/\s+/g, '').replace(/\.+$/, '');
        return units[key] || units[''];
    }

    document.querySelectorAll('input[data-quantity]').forEach(input => {
        // A field the server marked invalid stays so until it is changed.
        input.dataset.quantityServerError = input.classList.contains('is-invalid') ? 'true' : 'false';
        input.addEventListener('input', () => {
            input.dataset.quantityServerError = 'false';
            clean(input);
            check(input);
        });
        input.addEventListener('quantity-rule', () => check(input));

        const unitField = input.dataset.quantityUnitField && document.getElementById(input.dataset.quantityUnitField);
        if (unitField) {
            const follow = () => {
                const rule = unitRule(unitField.value);
                if (rule) {
                    applyRule(input, rule[0], rule[1], unitField.value.trim());
                }
            };
            unitField.addEventListener('input', follow);
        }
        if (input.value && input.dataset.quantityServerError !== 'true') {
            clean(input);
            check(input);
        }
    });
})();
