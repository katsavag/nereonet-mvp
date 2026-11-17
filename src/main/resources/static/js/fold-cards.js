// The group cards that can be closed (layout/parts :: foldCard, details[data-fold]), on the four long lists:
// Inventory, Count the inventory, New request, Count what arrived. Linked by those pages, before their own script.
// Only an enhancement: without it every card opens and closes the same, nothing is remembered and a locked card is
// kept open by its markup and styles alone.
//  - A locked card (.is-locked: a count's card with a row that needs the person) cannot be closed: its summary does
//    nothing, and a card closed any other way opens itself. window.nnFoldLock(card, locked) is for the page's script,
//    as the rows change: it sets the class, takes the summary out of the tab order, and opens a card it locks.
//  - Remembering: inside an element with data-fold-store="<page>:<vessel id>" (Inventory and New request only) the
//    cards the person closed (by their data-fold-id) are kept in localStorage on this device and closed again on the
//    next visit. Only a click or a key on the summary is remembered, never a card a script opened (the search). A
//    card with a refused field stays open. When storage is not available every card is simply open.
//  - A card opened or closed with its summary keeps its header in view and the focus (it may change column).
(function () {
    'use strict';

    const STORE = 'nn.fold.';

    function lock(card, locked) {
        const summary = card.querySelector(':scope > summary');
        card.classList.toggle('is-locked', locked);
        if (summary) {
            if (locked) {
                summary.setAttribute('tabindex', '-1');
            } else {
                summary.removeAttribute('tabindex');
            }
        }
        if (locked) {
            card.open = true;
        }
    }
    window.nnFoldLock = lock;

    // The card the person just opened or closed with its summary. In two flowing columns (.card-flow) the cards
    // rebalance at once, so that card may move: its header is scrolled back into view and keeps the focus.
    let clicked = null;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

    function keepInView(summary) {
        const box = summary.getBoundingClientRect();
        if (box.top < 0 || box.bottom > window.innerHeight) {
            summary.scrollIntoView({ block: 'nearest', behavior: reduceMotion.matches ? 'auto' : 'smooth' });
        }
        if (document.activeElement !== summary) {
            summary.focus({ preventScroll: true });
        }
    }

    document.querySelectorAll('details[data-fold]').forEach(card => {
        const summary = card.querySelector(':scope > summary');
        if (summary) {
            summary.addEventListener('click', event => {
                if (card.classList.contains('is-locked')) {
                    event.preventDefault();
                    card.open = true;
                    return;
                }
                clicked = card;
            });
        }
        card.addEventListener('toggle', () => {
            if (!card.open && card.classList.contains('is-locked')) {
                card.open = true;
            }
            if (summary && clicked === card) {
                clicked = null;
                keepInView(summary);
            }
        });
    });

    function read(key) {
        try {
            const closed = JSON.parse(window.localStorage.getItem(key));
            return Array.isArray(closed) ? closed : [];
        } catch (e) {
            return [];
        }
    }

    document.querySelectorAll('[data-fold-store]').forEach(area => {
        const key = STORE + area.dataset.foldStore;
        const cards = Array.from(area.querySelectorAll('details[data-fold][data-fold-id]'));
        const closed = read(key);
        cards.forEach(card => {
            if (closed.includes(card.dataset.foldId) && !card.querySelector('.is-invalid')) {
                card.open = false;
            }
            const summary = card.querySelector(':scope > summary');
            if (!summary) {
                return;
            }
            // The click comes before the card changes, so the card will be what it is not now.
            summary.addEventListener('click', () => {
                const closing = card.open;
                const now = read(key).filter(id => id !== card.dataset.foldId);
                if (closing) {
                    now.push(card.dataset.foldId);
                }
                try {
                    window.localStorage.setItem(key, JSON.stringify(now));
                } catch (e) {
                    // No storage (private mode, full): the card still opens and closes.
                }
            });
        });
    });
})();
