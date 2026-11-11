// Row "more" menus (layout/parts :: rowMenu) are Bootstrap dropdowns inside a native details element.
// The browser does not render the content of a closed details element, so keep it open while Bootstrap shows
// the menu. Without this script (or without Bootstrap) the details element opens on its own and shows the menu
// in place.
document.addEventListener('show.bs.dropdown', event => {
    const details = event.target.closest('details.row-menu');
    if (details) {
        details.open = true;
    }
});
document.addEventListener('hide.bs.dropdown', event => {
    const details = event.target.closest('details.row-menu');
    if (details) {
        details.open = false;
    }
});
