// New request and Edit request (templates/shiprequest/form.html). Only an enhancement: without this script the
// whole list shows and every button posts the form as it is.
//  - [data-request-search] (hidden until this script runs) filters the rows ([data-request-row], by data-name) by
//    name, ignoring case; a group's card (details[data-fold], layout/parts :: foldCard) with no match is hidden and
//    one with a match is opened, whatever was closed. What is typed stays in the hidden rows and in the closed
//    cards, and is sent with the form.
//  - The header of each group's card says how many of its items have a quantity ([data-fold-picked]: "3 picked").
//  - [data-request-search-empty] (a live region) says when nothing matches.
//  - Enter never sends the form from a field: in the search or a quantity it does nothing (the form's first button
//    could be "Add to request"); in the new item's fields it presses "Add to request".
//  - "Add to request" and "Remove" (the submit buttons inside #newItems) send the form with fetch and put the
//    #newItems of the page that comes back in place of the one here; the rest of the page (scroll, search, typed
//    quantities) is not touched. The pressed button is busy meanwhile (js/save.js, nnBusy). Then the focus goes to Name and
//    [data-request-live] says "Added: Saffron"; with errors, to the first wrong field; after Remove, to the card's
//    heading. When the request fails or the answer has no #newItems (signed out, the request decided meanwhile), the
//    form is posted the plain way.
(function () {
    'use strict';

    const CARD = 'newItems';

    document.querySelectorAll('[data-request-form]').forEach(form => {
        const input = form.querySelector('[data-request-search]');
        const empty = form.querySelector('[data-request-search-empty]');
        const live = form.querySelector('[data-request-live]');
        let sending = false;
        let plain = false;

        if (input) {
            form.querySelectorAll('[data-request-search-part]').forEach(part => { part.hidden = false; });
            input.addEventListener('input', () => {
                const query = input.value.trim().toLocaleLowerCase();
                let shown = 0;
                form.querySelectorAll('details[data-fold]').forEach(group => {
                    let inGroup = 0;
                    group.querySelectorAll('[data-request-row]').forEach(row => {
                        const match = !query || (row.dataset.name || '').includes(query);
                        row.hidden = !match;
                        inGroup += match ? 1 : 0;
                    });
                    group.hidden = inGroup === 0;
                    if (query && inGroup > 0) {
                        group.open = true;
                    }
                    shown += inGroup;
                });
                if (empty) {
                    empty.textContent = shown === 0 ? empty.dataset.text : '';
                }
            });
        }

        function picked() {
            form.querySelectorAll('details[data-fold]').forEach(group => {
                const fact = group.querySelector('[data-fold-picked]');
                if (!fact) {
                    return;
                }
                const count = Array.from(group.querySelectorAll('[data-request-row] input[data-quantity]'))
                    .filter(field => field.value.trim() !== '').length;
                fact.hidden = count === 0;
                fact.textContent = fact.dataset.text.replace('{n}', count);
            });
        }
        form.addEventListener('input', picked);
        picked();

        form.addEventListener('keydown', event => {
            const field = event.target;
            if (event.key !== 'Enter' || !(field instanceof HTMLInputElement)) {
                return;
            }
            event.preventDefault();
            const add = form.querySelector('[data-request-add]');
            if (add && field.closest('[data-request-new-item]')) {
                add.click();
            }
        });

        function show(fresh, adding, name) {
            const wrong = fresh.querySelector('.is-invalid');
            if (live) {
                live.textContent = adding && !wrong && name ? live.dataset.added.replace('{name}', name) : '';
            }
            const target = wrong || fresh.querySelector(adding ? '#newName' : '#' + CARD + 'Title');
            if (target) {
                target.focus({preventScroll: true}); // the card is where the button was pressed
            }
        }

        form.addEventListener('submit', event => {
            const button = event.submitter;
            const card = document.getElementById(CARD);
            if (plain || !button || !card || !card.contains(button) || !window.fetch || !window.DOMParser) {
                return;
            }
            event.preventDefault();
            if (sending) {
                return;
            }
            sending = true;
            const adding = button.name === 'add';
            const nameField = card.querySelector('#newName');
            const name = nameField ? nameField.value.trim() : '';
            const data = new URLSearchParams(new FormData(form));
            data.append(button.name, button.value);
            const working = window.nnBusy(button); // js/save.js
            fetch(form.getAttribute('action'), {method: 'POST', body: data, credentials: 'same-origin'})
                .then(response => {
                    if (!response.ok) {
                        throw new Error('HTTP ' + response.status);
                    }
                    return response.text();
                })
                .then(html => {
                    const fresh = new DOMParser().parseFromString(html, 'text/html').getElementById(CARD);
                    if (!fresh) {
                        throw new Error('No card in the answer');
                    }
                    working.done(); // the card that comes in has fresh buttons
                    card.replaceWith(fresh);
                    fresh.dispatchEvent(new CustomEvent('nn:fragment', {bubbles: true}));
                    show(fresh, adding, name);
                    sending = false;
                })
                .catch(() => {
                    sending = false;
                    working.done();
                    plain = true;
                    if (form.requestSubmit) {
                        form.requestSubmit(button);
                    } else {
                        button.click();
                    }
                    plain = false;
                });
        });
    });
})();
