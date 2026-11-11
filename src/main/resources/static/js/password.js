// Password fields (P13): sign-in and registration. Only enhancements: without this script the fields are ordinary
// password fields, the rules under the registration password are a plain list, and the forms submit as usual.
//  - [data-password-toggle]: the "Show" / "Hide" button inside a .password-field (layout/parts :: passwordToggle).
//    Hidden in the markup, shown here. Switches the field between type=password and type=text; its accessible name
//    follows ("Show password" / "Hide password") and the live region next to it says "Your password is visible /
//    hidden". Back to hidden before the form is sent, so the browser never stores it as plain text.
//  - [data-caps-lock-for]: "Caps Lock is on." under the field while it has focus and Caps Lock is on
//    (layout/parts :: capsLockHint).
//  - .password-rules: the registration checklist. Each rule gets is-met and the text "done" / "not yet" (data-met /
//    data-unmet on the list) for screen readers, as the user types. The server checks the same rules again.
(function () {
    'use strict';

    // Show / hide
    document.querySelectorAll('[data-password-toggle]').forEach(button => {
        const field = document.getElementById(button.dataset.passwordToggle);
        if (!field) {
            return;
        }
        const status = document.getElementById(field.id + 'Visibility');
        const set = (visible, announce) => {
            field.type = visible ? 'text' : 'password';
            button.textContent = visible ? button.dataset.textHide : button.dataset.textShow;
            button.setAttribute('aria-label', visible ? button.dataset.labelHide : button.dataset.labelShow);
            if (status && announce) {
                status.textContent = visible ? status.dataset.shown : status.dataset.hidden;
            }
        };
        button.addEventListener('click', () => set(field.type === 'password', true));
        if (field.form) {
            field.form.addEventListener('submit', () => set(false, false));
        }
        button.hidden = false;
        button.closest('.password-field')?.classList.add('has-toggle');
    });

    // Caps Lock
    document.querySelectorAll('[data-caps-lock-for]').forEach(hint => {
        const field = document.getElementById(hint.dataset.capsLockFor);
        if (!field) {
            return;
        }
        const show = on => {
            const text = on ? hint.dataset.text : '';
            if (hint.textContent !== text) {
                hint.textContent = text;
            }
        };
        const check = event => {
            if (typeof event.getModifierState === 'function') {
                show(event.getModifierState('CapsLock'));
            }
        };
        field.addEventListener('keydown', check);
        field.addEventListener('keyup', check);
        field.addEventListener('blur', () => show(false));
    });

    // Password rules
    document.querySelectorAll('.password-rules').forEach(list => {
        const field = document.getElementById(list.id.replace(/Rules$/, ''));
        if (!field) {
            return;
        }
        const rules = Array.from(list.querySelectorAll('[data-rule]'));
        const others = rules.map(rule => rule.dataset.match && document.getElementById(rule.dataset.match))
            .filter(Boolean);
        const met = rule => {
            if (rule.dataset.rule === 'min-length') {
                return field.value.length >= Number(rule.dataset.min);
            }
            const other = document.getElementById(rule.dataset.match);
            return Boolean(other && field.value !== '' && field.value === other.value);
        };
        const update = () => rules.forEach(rule => {
            const ok = met(rule);
            if (rule.dataset.state === String(ok)) {
                return; // unchanged: nothing new for the live region
            }
            rule.dataset.state = String(ok);
            rule.classList.toggle('is-met', ok);
            const state = rule.querySelector('.password-rule-state');
            if (state) {
                state.textContent = ', ' + (ok ? list.dataset.met : list.dataset.unmet);
            }
        });
        list.classList.add('is-live');
        update();
        // A live region only from here: the first state ("not yet", or "done" after a failed post) is not announced.
        list.setAttribute('aria-live', 'polite');
        [field, ...others].forEach(input => input.addEventListener('input', update));
    });
})();
