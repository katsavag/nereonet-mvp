// Notifications (P23): marking as read without reloading the page, on a slow link. Only an enhancement: without this
// script Mark as read and Mark all as read are ordinary POST forms that come back to the same page with a message.
//  - A form with data-notification-read (one notification) or data-notification-read-all (the page header) is sent
//    with fetch and Accept: application/json. The answer is the new unread count and the counters' words
//    (notification/NotificationController.Unread). The row (every row, for all) loses its dot, bold text, "Unread:"
//    and its Mark as read button; the sidebar entry's pill (layout/parts :: navItem, data-nav="notifications") and the
//    phone top bar's bell (#notificationBell) show the new count; Mark all as read goes once nothing is unread.
//    [data-notification-status] says what happened, for screen readers. Focus moves from the button that went to the
//    next Mark as read button, or to the page heading.
//  - Offline, or when the answer is not the count (the session expired, an error), the form is sent the ordinary way:
//    js/save.js then shows "Saving…", or says it was not sent.
//  - Back to the list after opening a notification: a page the browser kept in memory is loaded again, so the one just
//    opened no longer shows as unread and the counters are right.
(function () {
    'use strict';

    const status = document.querySelector('[data-notification-status]');
    if (!status) {
        return;
    }

    window.addEventListener('pageshow', event => {
        if (event.persisted) {
            window.location.reload();
        }
    });

    const heading = document.querySelector('main h1');

    function focusAfter(removed) {
        const buttons = Array.from(document.querySelectorAll('form[data-notification-read] button'));
        const next = buttons.find(button => removed.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING);
        if (next) {
            next.focus();
        } else if (heading) {
            heading.setAttribute('tabindex', '-1');
            heading.focus();
        }
    }

    function announce(text) {
        status.textContent = '';
        window.setTimeout(() => {
            status.textContent = text;
        }, 50);
    }

    function markRowRead(row) {
        row.classList.remove('is-unread');
        row.querySelectorAll('[data-unread-label]').forEach(label => label.remove());
        const form = row.querySelector('form[data-notification-read]');
        if (form) {
            form.remove();
        }
    }

    function shownCount(unread) {
        return unread > 99 ? '99+' : String(unread);
    }

    // The sidebar entry: "Notifications 3", with "3 unread" for screen readers; nothing at zero.
    function updateSidebar(data) {
        const link = document.querySelector('[data-nav="notifications"] .sidebar-link');
        if (!link) {
            return;
        }
        let pill = link.querySelector('.nav-count');
        if (data.unread === 0) {
            if (pill) {
                const words = pill.nextElementSibling;
                pill.remove();
                if (words && words.classList.contains('visually-hidden')) {
                    words.remove();
                }
            }
            return;
        }
        if (!pill) {
            pill = document.createElement('span');
            pill.className = 'nav-count num';
            pill.setAttribute('aria-hidden', 'true');
            const words = document.createElement('span');
            words.className = 'visually-hidden';
            link.append(pill, words);
        }
        pill.textContent = shownCount(data.unread);
        pill.nextElementSibling.textContent = data.countLabel;
    }

    // The phone top bar's bell: its label "Notifications, 3 unread" and the red badge; no badge at zero.
    function updateBell(data) {
        const bell = document.getElementById('notificationBell');
        if (!bell) {
            return;
        }
        bell.setAttribute('aria-label', data.bellLabel);
        let badge = bell.querySelector('.badge');
        if (data.unread === 0) {
            if (badge) {
                badge.remove();
            }
            return;
        }
        if (!badge) {
            badge = document.createElement('span');
            badge.className = 'position-absolute top-0 end-0 badge rounded-pill bg-danger';
            bell.append(badge);
        }
        badge.textContent = shownCount(data.unread);
    }

    function removeMarkAll() {
        const form = document.querySelector('form[data-notification-read-all]');
        if (!form) {
            return;
        }
        const actions = form.closest('.page-actions');
        (actions && actions.children.length === 1 ? actions : form).remove();
    }

    function send(form) {
        return fetch(form.action, {
            method: 'POST',
            body: new FormData(form),
            headers: { Accept: 'application/json' },
            credentials: 'same-origin'
        }).then(response => {
            const type = response.headers.get('Content-Type') || '';
            if (!response.ok || type.indexOf('application/json') < 0) {
                throw new Error('not the unread count');
            }
            return response.json();
        });
    }

    // The ordinary way: a full page with the message (or, offline, js/save.js's "not sent").
    function sendPlain(form, submitter) {
        form.dataset.plain = 'true';
        if (form.requestSubmit) {
            form.requestSubmit(submitter && submitter.form === form ? submitter : undefined);
        } else {
            form.submit();
        }
    }

    function handle(form, all) {
        form.addEventListener('submit', event => {
            if (form.dataset.plain || navigator.onLine === false || !window.fetch) {
                return;
            }
            event.preventDefault();
            if (form.dataset.sending) {
                return;
            }
            form.dataset.sending = 'true';
            const submitter = event.submitter;
            const buttons = Array.from(form.querySelectorAll('button'));
            buttons.forEach(button => {
                button.disabled = true;
            });
            send(form).then(data => {
                const rows = all ? Array.from(document.querySelectorAll('[data-notification].is-unread'))
                    : [form.closest('[data-notification]')];
                const focused = buttons.indexOf(document.activeElement) >= 0 || document.activeElement === document.body;
                const place = all ? (heading || form) : rows[0];
                rows.forEach(row => row && markRowRead(row));
                if (data.unread === 0) {
                    document.querySelectorAll('[data-notification].is-unread').forEach(markRowRead);
                    removeMarkAll();
                }
                updateSidebar(data);
                updateBell(data);
                announce(all ? status.dataset.all : status.dataset.one);
                if (focused) {
                    focusAfter(place);
                }
            }).catch(() => {
                buttons.forEach(button => {
                    button.disabled = false;
                });
                delete form.dataset.sending;
                sendPlain(form, submitter);
            });
        });
    }

    document.querySelectorAll('form[data-notification-read]').forEach(form => handle(form, false));
    document.querySelectorAll('form[data-notification-read-all]').forEach(form => handle(form, true));
})();
