// Shows every <time class="local-time" datetime="...Z"> (a UTC instant from the server) in the
// browser's time zone and the page language, e.g. "21 Oct 2026 · 14:40".
// Without JavaScript the server-rendered UTC text stays.
document.addEventListener('DOMContentLoaded', () => {
    const lang = document.documentElement.lang || 'en';
    const date = new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', year: 'numeric' });
    const time = new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    document.querySelectorAll('time.local-time').forEach(el => {
        const d = new Date(el.dateTime);
        if (!isNaN(d)) {
            el.textContent = date.format(d) + ' · ' + time.format(d);
        }
    });
});
