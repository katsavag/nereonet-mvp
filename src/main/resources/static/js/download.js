// The file an action prepared (P21). An export comes back to the list with "3 requests exported · Download again"
// (layout/parts :: flash), whose link (data-auto-download) is the GET URL of that export's file. This script starts
// that download once, as the page opens, so the export needs no further click; the page stays where it is. The
// message is shown once (a flash attribute), so a reload does not download again. Without the script the link is
// there to click.
(function () {
    'use strict';

    const link = document.querySelector('a[data-auto-download]');
    if (link) {
        link.click();
    }
})();
