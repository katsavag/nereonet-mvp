// The ship page's tab bar (ship/parts :: tabs). When it is wider than the screen it scrolls sideways; start with the
// active tab in view (centred when it can be), so on a phone the current tab is never hidden off to the right.
// Without this script the bar still scrolls and every tab is still a link.
document.querySelectorAll('.ship-tabs-list').forEach(list => {
    const active = list.querySelector('[aria-current="page"]');
    if (!active || list.scrollWidth <= list.clientWidth) {
        return;
    }
    const tab = active.closest('li');
    list.scrollLeft = tab.offsetLeft - (list.clientWidth - tab.offsetWidth) / 2;
});
