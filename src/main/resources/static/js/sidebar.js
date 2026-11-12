// The sidebar (P29, layout/layout.html).
//  - From 768px, "Expand menu" / "Collapse menu" (layout/parts :: sidebarToggle) switches between the 64px icon rail
//    and the full sidebar in place: body.sidebar-collapsed / sidebar-expanded, the button's aria-expanded and label,
//    and the cookie sidebar=expanded|collapsed that the server reads on the next page, so the choice is kept per
//    browser without a flash. Without this script the button sends its form and the server does the same.
//  - Below 768px (the office menu, a Bootstrap offcanvas): choosing an entry closes the menu at once, so it is not
//    left open while the next page loads over a slow link. The X, a tap outside and Esc close it through Bootstrap,
//    which also locks the page's scrolling while it is open and gives focus back to the menu button.
(() => {
    const ONE_YEAR = 365 * 24 * 60 * 60;
    const body = document.body;

    const toggle = document.querySelector('[data-sidebar-toggle]');
    if (toggle) {
        const label = toggle.querySelector('.sidebar-label');
        const state = toggle.form && toggle.form.querySelector('[data-sidebar-state]');
        toggle.addEventListener('click', event => {
            event.preventDefault();
            const expanded = !body.classList.contains('sidebar-expanded');
            body.classList.toggle('sidebar-expanded', expanded);
            body.classList.toggle('sidebar-collapsed', !expanded);
            document.cookie = 'sidebar=' + (expanded ? 'expanded' : 'collapsed') + '; path=/; max-age=' + ONE_YEAR
                + '; samesite=lax';
            toggle.setAttribute('aria-expanded', String(expanded));
            if (label) {
                label.textContent = expanded ? toggle.dataset.labelCollapse : toggle.dataset.labelExpand;
            }
            if (state) {
                state.value = expanded ? 'collapsed' : 'expanded';
            }
        });
    }

    const sidebar = document.getElementById('sidebar');
    if (sidebar && window.bootstrap) {
        sidebar.addEventListener('click', event => {
            if (sidebar.classList.contains('show') && event.target.closest('.sidebar-nav a[href], .sidebar-brand')) {
                bootstrap.Offcanvas.getOrCreateInstance(sidebar).hide();
            }
        });
    }
})();
