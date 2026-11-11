// Every time is shown in UTC, so office and ship read the same time for the same event (P11): the server renders
// <time class="local-time" datetime="...Z">21 Oct 2026 · 12:40 UTC</time> (layout/parts :: localTime).
// This script does not change that text. It adds the viewer's own time, in the page language, as a tooltip and as
// screen reader text: "Your time: 21 Oct 2026, 14:40". The wording comes from <body data-local-time> (message key
// time.local). Without JavaScript the UTC time is all there is, and it is complete.
document.addEventListener('DOMContentLoaded', () => {
    const lang = document.documentElement.lang || 'en';
    const template = document.body.dataset.localTime || 'Your time: {time}';
    const date = new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', year: 'numeric' });
    const time = new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    // Day, month, year in this order in every language, as on the server ("21 Oct 2026", "21 Οκτ 2026").
    const formatDate = d => {
        const parts = date.formatToParts(d);
        const part = type => (parts.find(p => p.type === type) || {}).value;
        return part('day') + ' ' + part('month') + ' ' + part('year');
    };
    document.querySelectorAll('time.local-time').forEach(el => {
        const d = new Date(el.dateTime);
        if (isNaN(d) || el.title) {
            return;
        }
        const text = template.replace('{time}', formatDate(d) + ', ' + time.format(d));
        el.title = text;
        const hidden = document.createElement('span');
        hidden.className = 'visually-hidden';
        hidden.textContent = ' ' + text;
        el.append(hidden);
    });
});
