// The confirmation dialog (P10): layout/parts :: confirmDialog, opened by the trigger of a layout/parts :: confirmForm.
// A form opts in with data attributes:
//   data-confirm-title    the heading, naming the record ("Deactivate MV Demo?")
//   data-confirm-body     the consequence, one sentence
//   data-confirm-label    the confirm button ("Deactivate")
//   data-confirm-tone     'danger' (red confirm button; Enter does not confirm) or 'neutral' (primary button)
//   data-confirm-dismiss  optional: the dismiss button instead of "Cancel"
// Its trigger, [data-confirm-open], is a link to the plain confirmation page. This script turns a click on it into
// the dialog; Confirm submits the form. Focus starts on Cancel; Esc, Cancel and the backdrop close the dialog and
// put focus back where it was. Without this script, or in a browser without <dialog>, the link opens the page, so a
// confirmForm never submits unconfirmed.
(function () {
    'use strict';

    const dialog = document.getElementById('confirmDialog');
    if (!dialog || typeof dialog.showModal !== 'function') {
        return;
    }
    const title = dialog.querySelector('#confirmDialogTitle');
    const body = dialog.querySelector('#confirmDialogBody');
    const cancel = dialog.querySelector('[data-confirm-cancel]');
    const ok = dialog.querySelector('[data-confirm-ok]');
    let form = null;
    let returnFocus = null;

    function open(target, trigger) {
        const data = target.dataset;
        const danger = data.confirmTone !== 'neutral';
        form = target;
        // A trigger inside a "more" menu is hidden once the menu closes: give focus back to the menu's button.
        const menu = trigger.closest('.dropdown');
        returnFocus = menu ? menu.querySelector('[data-bs-toggle="dropdown"]') : trigger;
        title.textContent = data.confirmTitle;
        body.textContent = data.confirmBody;
        ok.textContent = data.confirmLabel;
        cancel.textContent = data.confirmDismiss || dialog.dataset.cancelLabel;
        ok.className = 'btn ' + (danger ? 'btn-outline-danger' : 'btn-primary');
        ok.disabled = false;
        dialog.dataset.tone = danger ? 'danger' : 'neutral';
        dialog.showModal();
        cancel.focus();
    }

    document.addEventListener('click', event => {
        const trigger = event.target.closest('[data-confirm-open]');
        const target = trigger && trigger.closest('form[data-confirm-title]');
        if (!target || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
            return;
        }
        event.preventDefault();
        open(target, trigger);
    });

    cancel.addEventListener('click', () => dialog.close());

    ok.addEventListener('click', () => {
        // Once only: a second click on a slow connection must not post the action twice.
        ok.disabled = true;
        const target = form;
        returnFocus = null;
        dialog.close();
        target.submit();
    });

    // The backdrop: a click on the dialog element itself, outside its content.
    dialog.addEventListener('click', event => {
        if (event.target === dialog) {
            dialog.close();
        }
    });

    // Enter never confirms a danger dialog: on the confirm button it does nothing (Space or a click still do).
    dialog.addEventListener('keydown', event => {
        if (event.key === 'Enter' && dialog.dataset.tone === 'danger' && event.target === ok) {
            event.preventDefault();
        }
    });

    // Esc fires "cancel" and then "close"; every way out ends here.
    dialog.addEventListener('close', () => {
        form = null;
        if (returnFocus && returnFocus.isConnected) {
            returnFocus.focus();
        }
        returnFocus = null;
    });
})();
