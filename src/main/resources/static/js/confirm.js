// The confirmation dialog: layout/parts :: confirmDialog, opened by the trigger of a layout/parts :: confirmForm.
// A form opts in with data attributes:
//   data-confirm-title    the heading, naming the record ("Deactivate MV Demo?")
//   data-confirm-body     the consequence, one sentence
//   data-confirm-label    the confirm button ("Deactivate")
//   data-confirm-tone     'danger' (red confirm button; Enter does not confirm) or 'neutral' (primary button)
//   data-confirm-dismiss  optional: the dismiss button instead of "Cancel"
//   data-confirm-note     optional: the dialog also asks for an optional note with this label (at most
//                         data-confirm-note-max characters) and Confirm puts it in the form's input[name=note]
// Its trigger, [data-confirm-open], is a link to the plain confirmation page. This script turns a click on it into
// the dialog; Confirm submits the form. Focus starts on Cancel; Esc, Cancel and the backdrop close the dialog and
// put focus back where it was. Without this script, or in a browser without <dialog>, the link opens the page, so a
// confirmForm never submits unconfirmed. A trigger that sits outside its form (a row of a grid that is itself a
// form) names the form's id in data-confirm-form (layout/parts :: confirmTrigger). A form may hold a hidden
// ul[data-confirm-items], what will change; the dialog lists its items under the body.
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
    const noteBox = dialog.querySelector('[data-confirm-note-box]');
    const items = dialog.querySelector('[data-confirm-items-list]');
    const noteField = noteBox && noteBox.querySelector('input');
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
        if (items) {
            const lines = target.querySelectorAll('[data-confirm-items] > li');
            items.replaceChildren(...Array.from(lines, line => line.cloneNode(true)));
            items.hidden = lines.length === 0;
        }
        ok.textContent = data.confirmLabel;
        cancel.textContent = data.confirmDismiss || dialog.dataset.cancelLabel;
        ok.className = 'btn ' + (danger ? 'btn-outline-danger' : 'btn-primary');
        ok.disabled = false;
        dialog.dataset.tone = danger ? 'danger' : 'neutral';
        if (noteBox) {
            noteBox.hidden = !data.confirmNote;
            noteField.value = '';
            if (data.confirmNote) {
                noteBox.querySelector('[data-confirm-note-label]').textContent = data.confirmNote;
                if (data.confirmNoteMax) {
                    noteField.maxLength = Number(data.confirmNoteMax);
                }
            }
        }
        dialog.showModal();
        cancel.focus();
    }

    document.addEventListener('click', event => {
        const trigger = event.target.closest('[data-confirm-open]');
        const target = trigger && (trigger.dataset.confirmForm
            ? document.getElementById(trigger.dataset.confirmForm)
            : trigger.closest('form[data-confirm-title]'));
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
        const note = target.dataset.confirmNote && target.querySelector('input[name=note]');
        if (note && noteField) {
            note.value = noteField.value;
        }
        // requestSubmit fires "submit", so js/save.js sees it: not sent while offline.
        if (typeof target.requestSubmit === 'function') {
            target.requestSubmit();
        } else {
            target.submit();
        }
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
